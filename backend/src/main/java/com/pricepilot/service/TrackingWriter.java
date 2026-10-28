package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.Tracker;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.repo.TrackerRepository;
import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The transactional half of adding, changing and removing tracked products.
 *
 * <p>A separate bean from {@link ProductTrackingService} for the same two reasons
 * {@code RefreshWriter} is separate from {@code ListingRefreshService}. First, Spring implements
 * {@code @Transactional} with a proxy, and a transactional method called from another method of the
 * same bean bypasses that proxy and silently runs with no transaction at all. Second, and more
 * importantly here: adding a product involves a real twenty-second fetch of a retailer page, and
 * none of that may happen with a database connection checked out. Every method in this class opens a
 * transaction, runs a few short statements, and commits.
 *
 * <h2>On the unique constraints</h2>
 * Two of the writes below race. Two users adding the same Flipkart URL in the same second both see
 * no existing listing and both try to insert one; one user double-clicking "track" sends two
 * requests that both pass the tracker check. Both races are closed by unique constraints in the
 * database — {@code listing.url_hash} and {@code ux_tracker_user_product} — rather than by locking,
 * and the {@link DataIntegrityViolationException} is caught and turned into "the other request won,
 * use its row". Checking first and inserting second is still worth doing because it handles the
 * overwhelmingly common case without an exception; the catch is what makes it correct.
 */
@Service
public class TrackingWriter {

    private static final Logger log = LoggerFactory.getLogger(TrackingWriter.class);

    private static final int TITLE_LIMIT = 500;
    private static final int IMAGE_URL_LIMIT = 1000;
    private static final int NOTE_LIMIT = 500;

    /**
     * Fallback used only when a page yielded a price but no readable title. The product is real and
     * worth tracking; it just needs something to be called until a later check reads a title.
     */
    private static final String UNTITLED = "Untitled product";

    private final ProductRepository products;
    private final ListingRepository listings;
    private final TrackerRepository trackers;
    private final AppProperties props;

    public TrackingWriter(ProductRepository products,
                          ListingRepository listings,
                          TrackerRepository trackers,
                          AppProperties props) {
        this.products = products;
        this.listings = listings;
        this.trackers = trackers;
        this.props = props;
    }

    // ------------------------------------------------------------------ creating

    /**
     * Creates the product and its first listing from a page that has already been fetched.
     *
     * <p>The fetch happens before this call, not inside it, which is why the title and image are
     * passed in rather than looked up. That ordering is deliberate: {@code product.title} is
     * {@code NOT NULL} and {@code RefreshWriter.enrichProduct} only ever <em>fills</em> an empty
     * title, never overwrites one. Creating the row first with a placeholder would therefore make the
     * placeholder permanent. Fetching first costs nothing extra — the page has to be read anyway to
     * know the URL is trackable at all — and means a product row only ever exists for a page that
     * genuinely produced a price.
     *
     * @return the new listing, with its product attached
     * @throws DataIntegrityViolationException when another request inserted the same
     *         {@code url_hash} first. Deliberately not caught here: the whole transaction, product
     *         row included, must roll back so there is no orphan product, and only the caller — which
     *         is outside any transaction — can then re-resolve the winning listing by its hash and
     *         carry on. {@link ProductTrackingService#track} does exactly that.
     */
    @Transactional
    public Listing createProductAndListing(UrlCanonicalizer.Canonical canonical, ProductSnapshot snapshot) {
        Product product = new Product();
        product.setTitle(titleOf(snapshot));
        product.setImageUrl(cap(snapshot.imageUrl(), IMAGE_URL_LIMIT));
        product.setCurrency(snapshot.currency() == null ? "INR" : snapshot.currency());
        products.save(product);

        Listing listing = new Listing();
        listing.setProduct(product);
        listing.setRetailer(canonical.retailer());
        listing.setUrl(canonical.url());
        listing.setUrlHash(canonical.hash());
        listing.setTitle(titleOf(snapshot));
        listing.setStatus(ListingStatus.ACTIVE);
        // The first listing of a product represents it in list views. The URL the user pasted is the
        // one they chose, so it is the right default for that role.
        listing.setPrimary(true);
        listing.setCheckIntervalMinutes(props.getScheduler().getInitialIntervalMinutes());
        // nextCheckAt is left null so Listing.onCreate() sets it to now. The price point for the
        // fetch that just happened is written immediately after this, and the scheduler picking the
        // listing up early is harmless — the freshness window turns that into a SKIPPED_FRESH.
        listings.save(listing);
        listings.flush();

        log.info("Created product {} with listing {} at {}",
                product.getId(), listing.getId(), canonical.host());
        return listing;
    }

    /**
     * Adds a second retailer's page to an existing product.
     *
     * @return the new listing, or empty when the product has reached its listing cap or the URL was
     *         inserted by a concurrent request
     */
    @Transactional
    public Optional<Listing> addListing(Long productId,
                                        UrlCanonicalizer.Canonical canonical,
                                        ProductSnapshot snapshot) {
        Product product = products.findById(productId).orElse(null);
        if (product == null) {
            return Optional.empty();
        }

        long existing = listings.findByProductId(productId).size();
        if (existing >= props.getLimits().getMaxListingsPerProduct()) {
            log.debug("Product {} already has {} listings; not adding {}",
                    productId, existing, canonical.url());
            return Optional.empty();
        }

        Listing listing = new Listing();
        listing.setProduct(product);
        listing.setRetailer(canonical.retailer());
        listing.setUrl(canonical.url());
        listing.setUrlHash(canonical.hash());
        listing.setTitle(cap(snapshot == null ? null : snapshot.title(), TITLE_LIMIT));
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPrimary(false);
        listing.setCheckIntervalMinutes(props.getScheduler().getInitialIntervalMinutes());

        try {
            listings.save(listing);
            // Forces the insert now rather than at commit, so a url_hash collision surfaces here
            // where it can be handled instead of as an exception thrown out of the commit itself.
            listings.flush();
        } catch (DataIntegrityViolationException e) {
            log.debug("Listing for {} was created concurrently; skipping", canonical.url());
            return Optional.empty();
        }
        return Optional.of(listing);
    }

    /**
     * Starts tracking a product for a user, or reactivates a tracker they had switched off.
     *
     * <p>Reactivating rather than inserting matters: a user who untracks and retracks a product gets
     * their old tracker back, which keeps their alert history attached to it. It is also what makes
     * the unique constraint on (user, product) survivable instead of a 500.
     */
    @Transactional
    public Tracker trackFor(AppUser user, Long productId, BigDecimal targetPrice,
                            boolean notifyOnDeal, String note) {
        Tracker existing = trackers.findByUserIdAndProductId(user.getId(), productId).orElse(null);
        if (existing != null) {
            existing.setActive(true);
            if (targetPrice != null) {
                existing.setTargetPrice(targetPrice);
            }
            existing.setNotifyOnDeal(notifyOnDeal);
            if (note != null) {
                existing.setNote(cap(note, NOTE_LIMIT));
            }
            return existing;
        }

        requireTrackerCapacity(user.getId());

        Product product = products.findById(productId)
                .orElseThrow(() -> ApiException.notFound("That product"));

        Tracker tracker = new Tracker();
        tracker.setUser(user);
        tracker.setProduct(product);
        tracker.setTargetPrice(targetPrice);
        tracker.setNotifyOnDeal(notifyOnDeal);
        tracker.setNote(cap(note, NOTE_LIMIT));
        tracker.setActive(true);

        try {
            trackers.save(tracker);
            trackers.flush();
        } catch (DataIntegrityViolationException e) {
            // A double-clicked button. The other request created the row; return it rather than
            // failing, because from the user's point of view both clicks meant the same thing.
            log.debug("Tracker for user {} / product {} was created concurrently",
                    user.getId(), productId);
            return trackers.findByUserIdAndProductId(user.getId(), productId)
                    .orElseThrow(() -> ApiException.conflict("tracker_conflict",
                            "That product could not be added just now. Please try again."));
        }
        return tracker;
    }

    // ------------------------------------------------------------------ resolving

    /**
     * Looks up a listing already tracking this URL, flattened into plain values.
     *
     * <p>The flattening is the point. {@code Listing.product} is a lazy association, and the caller
     * runs outside any transaction, so handing back the entity would mean the first call to
     * {@code getProduct().getTitle()} throws a {@code LazyInitializationException} — the sort of bug
     * that works in a test with an open session and fails in production. Reading the fields here,
     * inside the transaction, and returning a record makes that impossible rather than merely
     * unlikely.
     *
     * @return the existing listing's identifiers, or empty when this URL is new to the system
     */
    @Transactional(readOnly = true)
    public Optional<ExistingListing> findExisting(String urlHash) {
        return listings.findByUrlHash(urlHash)
                .map(listing -> new ExistingListing(
                        listing.getId(),
                        listing.getProduct().getId(),
                        listing.getStatus(),
                        listing.getProduct().getTitle()));
    }

    /** A URL already in the system, and the product it belongs to. */
    public record ExistingListing(Long listingId, Long productId, ListingStatus status, String productTitle) {
    }

    // ------------------------------------------------------------------ changing

    /**
     * Updates a tracker's alert preferences.
     *
     * <p>{@code targetPrice} is tri-state on purpose and the API mirrors it: absent from the request
     * means "leave it alone", a number means "set it", and an explicit null means "clear it". Without
     * the distinction there is no way to remove a target once set, which is a small gap that produces
     * a steady trickle of unwanted alerts.
     *
     * <p>Returns flat values rather than the entity, for the same reason {@link #findExisting} does:
     * the caller is a controller running outside this transaction, and handing back a managed entity
     * whose {@code product} association is lazy invites a {@code LazyInitializationException} during
     * JSON serialisation — a failure that depends on the mapping rather than on the code, and so is
     * invisible when reading the controller.
     */
    @Transactional
    public TrackerSettings updateTracker(Long userId, Long trackerId, BigDecimal targetPrice,
                                         boolean clearTarget, Boolean notifyOnDeal, String note) {
        Tracker tracker = requireOwnTracker(userId, trackerId);
        if (clearTarget) {
            tracker.setTargetPrice(null);
        } else if (targetPrice != null) {
            tracker.setTargetPrice(targetPrice);
        }
        if (notifyOnDeal != null) {
            tracker.setNotifyOnDeal(notifyOnDeal);
        }
        if (note != null) {
            tracker.setNote(note.isBlank() ? null : cap(note, NOTE_LIMIT));
        }
        return settingsOf(tracker);
    }

    /** A tracker's alert preferences, as stored. */
    public record TrackerSettings(Long trackerId, Long productId, BigDecimal targetPrice,
                                  boolean notifyOnDeal, String note, boolean active) {
    }

    private static TrackerSettings settingsOf(Tracker tracker) {
        return new TrackerSettings(
                tracker.getId(),
                tracker.getProduct().getId(),
                tracker.getTargetPrice(),
                tracker.isNotifyOnDeal(),
                tracker.getNote(),
                tracker.isActive());
    }

    /**
     * Stops tracking, without deleting anything.
     *
     * <p>Deactivation rather than deletion, for three reasons. The user's alert history stays
     * readable; retracking later restores their settings; and the listing's price history is shared
     * with every other user watching the same URL, so a delete would either destroy data that is not
     * this user's to destroy or require a reference count. Deactivating sidesteps all of it, and the
     * scheduler already lets listings nobody is waiting on drift to the maximum interval, so an
     * abandoned product costs almost nothing to keep.
     */
    @Transactional
    public void untrack(Long userId, Long trackerId) {
        Tracker tracker = requireOwnTracker(userId, trackerId);
        tracker.setActive(false);
    }

    /**
     * Fails unless this user tracks this product.
     *
     * <p>The authorisation check in front of every product action — refresh, analyse, add a retailer,
     * change the primary listing. It lives here, beside {@code requireOwnTracker}, so that every rule
     * about who may touch what is in one class rather than spread across controllers where one
     * endpoint can quietly be written without it.
     *
     * <p>A deactivated tracker still passes. Untracking is reversible by design, and someone looking
     * at the history of something they used to watch is not trespassing.
     */
    @Transactional(readOnly = true)
    public void requireTracking(Long userId, Long productId) {
        if (trackers.findByUserIdAndProductId(userId, productId).isEmpty()) {
            throw ApiException.notYours("product");
        }
    }

    /** Makes one listing the one whose price represents the product in list views. */
    @Transactional
    public void setPrimaryListing(Long productId, Long listingId) {
        List<Listing> all = listings.findByProductId(productId);
        boolean found = all.stream().anyMatch(listing -> listing.getId().equals(listingId));
        if (!found) {
            throw ApiException.notFound("That listing");
        }
        for (Listing listing : all) {
            listing.setPrimary(listing.getId().equals(listingId));
        }
    }

    // ------------------------------------------------------------------ guards

    /**
     * Enforces the per-user product cap.
     *
     * <p>Counts active trackers only, so untracking frees a slot. The cap exists because every
     * tracked product is a recurring cost in retailer requests and Claude tokens against a prepaid
     * balance — this is the one limit that keeps a public demo from being able to spend the whole
     * budget.
     */
    private void requireTrackerCapacity(Long userId) {
        int max = props.getLimits().getMaxTrackedProductsPerUser();
        long active = trackers.countByUserIdAndActiveTrue(userId);
        if (active >= max) {
            throw ApiException.limitReached(
                    "You are tracking the maximum of " + max + " products. Remove one to add "
                    + "another.");
        }
    }

    /**
     * Loads a tracker and proves it belongs to the caller.
     *
     * <p>Reports 404 for someone else's tracker rather than 403 — see
     * {@link ApiException#notYours(String)} for why that distinction matters.
     */
    private Tracker requireOwnTracker(Long userId, Long trackerId) {
        Tracker tracker = trackers.findById(trackerId)
                .orElseThrow(() -> ApiException.notFound("That tracker"));
        if (!tracker.getUser().getId().equals(userId)) {
            throw ApiException.notYours("That tracker");
        }
        return tracker;
    }

    // ------------------------------------------------------------------ helpers

    private static String titleOf(ProductSnapshot snapshot) {
        String title = cap(snapshot.title(), TITLE_LIMIT);
        return title == null || title.isBlank() ? UNTITLED : title;
    }

    private static String cap(String value, int limit) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }
}
