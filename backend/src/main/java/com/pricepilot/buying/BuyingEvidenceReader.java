package com.pricepilot.buying;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class BuyingEvidenceReader {
    private final ListingRepository listings;
    private final PricePointRepository prices;
    private final BuyingHistory history;
    public BuyingEvidenceReader(ListingRepository listings, PricePointRepository prices, BuyingHistory history) {
        this.listings = listings; this.prices = prices; this.history = history;
    }

    @Transactional(readOnly = true)
    public BuyingModels.History read(Long productId, Long selectedListingId, Instant now) {
        List<Listing> available = listings.findByProductId(productId);
        if (selectedListingId != null && available.stream().noneMatch(listing -> listing.getId().equals(selectedListingId))) {
            throw ApiException.badRequest("Choose a retailer listing belonging to this tracked product.");
        }
        Long selected = selectedListingId;
        if (selected == null) {
            selected = available.stream().filter(listing -> listing.isPrimary() && listing.getStatus() != ListingStatus.DEAD)
                    .findFirst().or(() -> available.stream().filter(listing -> listing.getStatus() != ListingStatus.DEAD).findFirst())
                    .map(Listing::getId).orElse(null);
        }
        return history.analyze(available, prices.historyForProduct(productId, now.minus(Duration.ofDays(365))),
                prices.latestObservations(productId), selected, now);
    }
}
