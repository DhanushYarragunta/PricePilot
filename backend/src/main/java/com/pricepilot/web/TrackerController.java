package com.pricepilot.web;

import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.TrackingWriter;
import com.pricepilot.web.dto.ApiRequests;
import com.pricepilot.web.dto.ApiViews;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's own settings for a product they watch: target price, alert preference, private note.
 *
 * <p>Separate from {@code ProductController} because the two operate on different things, and the
 * distinction is the heart of the data model rather than a filing decision. A <em>product</em> and its
 * listings and price history are shared: four people watching the same Flipkart URL read the same
 * rows, which is what makes the price history long and the scraping cheap. A <em>tracker</em> is one
 * person's relationship to that product, and is the only part they own. So these endpoints are keyed
 * by tracker id and resolve ownership from it, while the product endpoints are keyed by product id
 * and have to prove a tracker exists before doing anything.
 *
 * <p>Injects {@link TrackingWriter} directly rather than going through an application service. The
 * tracker row is exactly what that class owns, every operation here is a single short transaction with
 * no network call in it, and adding a pass-through layer would mean two routes to the same write —
 * which is how one of them eventually drifts.
 */
@RestController
@RequestMapping("/api/trackers")
public class TrackerController {

    private final TrackingWriter writer;

    public TrackerController(TrackingWriter writer) {
        this.writer = writer;
    }

    /**
     * Changes alert preferences.
     *
     * <p>PATCH, not PUT, and the request record is nullable throughout: the dashboard has a target
     * price field and a notification toggle that are edited independently, so a PUT would force the
     * client to send back the fields it did not touch and would lose whichever of them it got wrong.
     * Clearing a target price needs {@code clearTarget: true} — see {@code ApiRequests.UpdateTracker}
     * for why a null cannot carry that meaning on its own.
     */
    @PatchMapping("/{trackerId}")
    public ApiViews.TrackerSettings update(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable Long trackerId,
                                           @Valid @RequestBody ApiRequests.UpdateTracker request) {
        TrackingWriter.TrackerSettings settings = writer.updateTracker(
                caller.id(),
                trackerId,
                request.targetPrice(),
                request.shouldClearTarget(),
                request.notifyOnDeal(),
                request.note());
        return view(settings);
    }

    /**
     * Stops watching a product.
     *
     * <p>Deactivates the tracker; it deletes nothing. The price history belongs to the listing, which
     * is shared with everyone else watching the same URL, so this user has nothing to delete there —
     * and keeping their own tracker means retracking later restores their target price and reattaches
     * their alert history instead of starting over.
     *
     * <p>204 rather than the updated tracker, because the row the client is holding is about to leave
     * the screen. Returning its new state would be describing something nobody is looking at.
     */
    @DeleteMapping("/{trackerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void untrack(@AuthenticationPrincipal AuthenticatedUser caller,
                        @PathVariable Long trackerId) {
        writer.untrack(caller.id(), trackerId);
    }

    private static ApiViews.TrackerSettings view(TrackingWriter.TrackerSettings settings) {
        return new ApiViews.TrackerSettings(
                settings.trackerId(),
                settings.productId(),
                settings.targetPrice(),
                settings.notifyOnDeal(),
                settings.note(),
                settings.active());
    }
}
