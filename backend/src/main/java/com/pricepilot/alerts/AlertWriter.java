package com.pricepilot.alerts;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AlertKind;
import com.pricepilot.domain.AlertLog;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.Tracker;
import com.pricepilot.repo.AlertLogRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.repo.TrackerRepository;
import com.pricepilot.schedule.RefreshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides who should be told about a price change, and writes the alert rows.
 *
 * <p>Split from {@link AlertService} for the same two reasons the scheduler is split: a
 * {@code @Transactional} method called from inside the same bean silently runs without a
 * transaction, and an SMTP send must never happen with a database connection checked out. Everything
 * here is database work in a short transaction. The sending happens afterwards, in the other class.
 *
 * <p>Rows are written <em>before</em> any email is attempted, and they are written whether or not
 * email is configured at all. That ordering is deliberate. The in-app alert feed is the primary
 * delivery channel and works with SMTP switched off entirely; email is a convenience layered on top.
 * It also means a crash between writing and sending loses an email, not the record of the event —
 * the opposite ordering would produce alerts the user received but the app has no memory of.
 */
@Service
public class AlertWriter {

    private static final Logger log = LoggerFactory.getLogger(AlertWriter.class);

    private static final int MESSAGE_LIMIT = 600;
    private static final int ERROR_LIMIT = 400;

    /**
     * A price has to fall by at least this much before a plain drop is worth an email. Below it the
     * alert is noise: a ₹200 move on a ₹80,000 laptop is not news, and a product oscillating by a
     * few rupees would otherwise mail someone every day until they turned alerts off.
     */
    private static final int MIN_DROP_PERCENT_FOR_DEAL = 5;

    private final TrackerRepository trackers;
    private final AlertLogRepository alerts;
    private final ProductRepository products;
    private final AppProperties props;

    public AlertWriter(TrackerRepository trackers,
                       AlertLogRepository alerts,
                       ProductRepository products,
                       AppProperties props) {
        this.trackers = trackers;
        this.alerts = alerts;
        this.products = products;
        this.props = props;
    }

    /**
     * Works out which watchers of this product should hear about the observation that was just
     * stored, and records an alert for each.
     *
     * @return the alerts to deliver, in the order they were written; empty when nobody needs telling
     */
    @Transactional
    public List<PendingAlert> fanOutPriceAlerts(RefreshResult refresh) {
        Product product = products.findById(refresh.productId()).orElse(null);
        if (product == null) {
            return List.of();
        }

        List<Tracker> watchers = trackers.findActiveWatchers(refresh.productId());
        if (watchers.isEmpty()) {
            return List.of();
        }

        List<PendingAlert> pending = new ArrayList<>();
        for (Tracker tracker : watchers) {
            AlertKind kind = chooseKind(tracker, refresh);
            if (kind == null) {
                continue;
            }
            PendingAlert alert = record(tracker, product, kind, refresh.price(),
                    refresh.previousPrice(), refresh.retailer(),
                    headlineFor(kind, product, refresh, tracker.getTargetPrice()));
            if (alert != null) {
                pending.add(alert);
            }
        }
        if (!pending.isEmpty()) {
            log.info("Raised {} alert(s) for product {} at {}",
                    pending.size(), refresh.productId(), Money.inr(refresh.price()));
        }
        return pending;
    }

    /**
     * Raises alerts for a favourable verdict from Claude.
     *
     * <p>Separate from the price path because the trigger is different in kind. A price alert fires
     * on a number crossing a line the user drew; this one fires on a judgement about whether the
     * current price is good <em>relative to its own history</em>, which is the thing a human would
     * otherwise have to work out by staring at a chart. Only watchers who asked for deal alerts get
     * it, and only when they have not had one recently.
     */
    @Transactional
    public List<PendingAlert> fanOutDealAlerts(Long productId, DealAnalysis analysis) {
        if (analysis == null || analysis.getVerdict() == null || !analysis.getVerdict().isFavourable()) {
            return List.of();
        }
        Product product = products.findById(productId).orElse(null);
        if (product == null) {
            return List.of();
        }

        List<PendingAlert> pending = new ArrayList<>();
        for (Tracker tracker : trackers.findActiveWatchers(productId)) {
            if (!tracker.isNotifyOnDeal()) {
                continue;
            }
            PendingAlert alert = record(tracker, product, AlertKind.DEAL_DETECTED,
                    analysis.getAnalysedPrice(), null, null,
                    dealHeadline(product, analysis));
            if (alert != null) {
                pending.add(alert);
            }
        }
        return pending;
    }

    /** Flips an alert to delivered once the mail server has accepted it. */
    @Transactional
    public void markDelivered(Long alertLogId) {
        alerts.findById(alertLogId).ifPresent(alert -> {
            alert.setDelivered(true);
            alert.setDeliveryError(null);
        });
    }

    /**
     * Records why an email did not go out. The alert itself stays — the user still sees it in the
     * app — and the error is kept so a misconfigured mail server is diagnosable from the ops page
     * rather than from grepping logs.
     */
    @Transactional
    public void markFailed(Long alertLogId, String error) {
        alerts.findById(alertLogId).ifPresent(alert -> {
            alert.setDelivered(false);
            alert.setDeliveryError(cap(error, ERROR_LIMIT));
        });
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Which single alert this tracker should get, or {@code null} for none.
     *
     * <p>At most one. A product hitting a user's target price while also reaching an all-time low
     * while also coming back into stock is one event, and sending three emails about it would be
     * indistinguishable from a bug. They are checked strongest-first: an explicit target the user
     * set themselves beats a record low, which beats a restock, which beats a general drop.
     */
    private AlertKind chooseKind(Tracker tracker, RefreshResult refresh) {
        if (!refresh.inStock() || refresh.price() == null) {
            return null;
        }
        BigDecimal target = tracker.getTargetPrice();
        if (target != null && refresh.price().compareTo(target) <= 0) {
            return AlertKind.TARGET_HIT;
        }
        if (!tracker.isNotifyOnDeal()) {
            // No target set and deal alerts switched off: this user wants the dashboard, not email.
            return null;
        }
        if (refresh.allTimeLow()) {
            return AlertKind.ALL_TIME_LOW;
        }
        if (refresh.cameBackInStock()) {
            return AlertKind.BACK_IN_STOCK;
        }
        Integer drop = refresh.dropPercent();
        if (drop != null && drop >= MIN_DROP_PERCENT_FOR_DEAL) {
            return AlertKind.DEAL_DETECTED;
        }
        return null;
    }

    /**
     * Writes one alert row, unless the cooldown says this user has heard it recently.
     *
     * <p>The cooldown is per tracker and per kind. Per kind matters: a product drifting down past a
     * target over a week should not re-notify every day, but if it then goes out of stock and comes
     * back, that is genuinely new information and should not be suppressed by the earlier target
     * alert.
     */
    private PendingAlert record(Tracker tracker,
                                Product product,
                                AlertKind kind,
                                BigDecimal price,
                                BigDecimal previousPrice,
                                String retailer,
                                String headline) {
        if (price == null) {
            return null;
        }
        Instant since = Instant.now().minus(Duration.ofHours(props.getAlerts().getCooldownHours()));
        if (alerts.existsRecent(tracker.getId(), kind, since)) {
            log.debug("Suppressing {} for tracker {} — one was sent inside the cooldown window",
                    kind, tracker.getId());
            return null;
        }

        AlertLog alert = new AlertLog();
        alert.setTracker(tracker);
        alert.setKind(kind);
        alert.setPrice(price);
        alert.setMessage(cap(headline, MESSAGE_LIMIT));
        alert.setDelivered(false);
        alerts.save(alert);

        tracker.setLastAlertAt(Instant.now());

        AppUser user = tracker.getUser();
        return new PendingAlert(
                alert.getId(),
                user == null ? null : user.getEmail(),
                user == null ? null : user.getDisplayName(),
                kind,
                product.getId(),
                product.getTitle(),
                retailer,
                price,
                previousPrice,
                tracker.getTargetPrice(),
                headline);
    }

    // ------------------------------------------------------------------ wording

    private String headlineFor(AlertKind kind, Product product, RefreshResult refresh, BigDecimal target) {
        String name = product.getTitle();
        String now = Money.inr(refresh.price());
        String where = Money.retailerName(refresh.retailer());
        String saved = Money.savings(refresh.previousPrice(), refresh.price());

        return switch (kind) {
            case TARGET_HIT -> name + " is now " + now + " at " + where
                    + ", at or below your target of " + Money.inr(target) + ".";
            case ALL_TIME_LOW -> name + " has hit " + now + " at " + where
                    + " — the lowest price since tracking began"
                    + (saved == null ? "." : ", down " + saved + " from the last check.");
            case BACK_IN_STOCK -> name + " is available again at " + where + " for " + now + ".";
            case DEAL_DETECTED -> name + " has dropped to " + now + " at " + where
                    + (saved == null ? "." : ", down " + saved + ".");
        };
    }

    private String dealHeadline(Product product, DealAnalysis analysis) {
        String headline = analysis.getHeadline();
        if (headline != null && !headline.isBlank()) {
            return headline;
        }
        // Falls back to a plain statement of fact rather than inventing enthusiasm the analysis did
        // not express.
        return product.getTitle() + " is rated " + analysis.getVerdict()
               + " at " + Money.inr(analysis.getAnalysedPrice()) + ".";
    }

    private static String cap(String value, int limit) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit - 1) + "…";
    }
}
