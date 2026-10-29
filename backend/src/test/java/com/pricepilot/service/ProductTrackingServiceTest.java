package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.*;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.schedule.*;
import com.pricepilot.scrape.*;
import com.pricepilot.scrape.rules.HostRule;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductTrackingServiceTest {
    @Test void anExistingCanonicalListingIsReusedWithoutScrapingOrNewHistory() {
        UrlCanonicalizer canonicalizer = mock(UrlCanonicalizer.class);
        ScrapeEngine engine = mock(ScrapeEngine.class);
        TrackingWriter writer = mock(TrackingWriter.class);
        RefreshWriter refreshWriter = mock(RefreshWriter.class);
        SiblingDiscoveryService discovery = mock(SiblingDiscoveryService.class);
        ProductTrackingService service = new ProductTrackingService(canonicalizer, engine, writer,
                refreshWriter, mock(ListingRefreshService.class), discovery, mock(ListingRepository.class), new AppProperties());
        AppUser user = new AppUser(); user.setId(1L);
        var canonical = new UrlCanonicalizer.Canonical("https://www.amazon.in/dp/B07CR28HLL", "amazon.in", "same-hash", HostRule.builder("amazon.in").build());
        when(canonicalizer.canonicalize("variant URL")).thenReturn(canonical);
        when(writer.findExisting("same-hash")).thenReturn(Optional.of(new TrackingWriter.ExistingListing(2L, 3L, ListingStatus.ACTIVE, "USB C Hub")));
        Tracker tracker = new Tracker(); tracker.setId(4L);
        when(writer.trackFor(user, 3L, null, false, null)).thenReturn(tracker);
        var result = service.track(user, "variant URL", null, false, null);
        assertThat(result.productId()).isEqualTo(3L);
        verifyNoInteractions(engine, refreshWriter, discovery);
        verify(writer, never()).createProductAndListing(any(), any());
    }
}
