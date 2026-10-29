package com.pricepilot.schedule;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.*;
import com.pricepilot.repo.*;
import com.pricepilot.scrape.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshWriterTest {
    private final ListingRepository listings=mock(ListingRepository.class);
    private final PricePointRepository prices=mock(PricePointRepository.class);
    private final ScrapeAttemptRepository attempts=mock(ScrapeAttemptRepository.class);
    private final RefreshWriter writer=new RefreshWriter(listings,prices,attempts,new IntervalPolicy(new AppProperties()));
    private Listing listing;
    private Instant verifiedAt;
    @BeforeEach void setup() {
        Product product=new Product();product.setId(1L);product.setTitle("USB C Hub");
        listing=new Listing();listing.setId(2L);listing.setProduct(product);listing.setRetailer("Amazon");
        listing.setCheckIntervalMinutes(180);listing.setStatus(ListingStatus.ACTIVE);
        verifiedAt=Instant.now().minusSeconds(3600);listing.setLastSuccessAt(verifiedAt);
        when(listings.findWithProductById(2L)).thenReturn(Optional.of(listing));
        PricePoint old=new PricePoint();old.setPrice(new BigDecimal("1609"));old.setCapturedAt(verifiedAt);
        when(prices.findFirstByListingIdOrderByCapturedAtDesc(2L)).thenReturn(Optional.of(old));
    }
    @Test void failurePreservesHistoryAndVerificationTimestamp() {
        var result=ScrapeResult.aborted(ScrapeOutcome.TIMEOUT,"amazon.in","https://amazon.in/dp/B07CR28HLL",null,1000,"Timed out");
        writer.recordFailure(2L,result,TriggerSource.MANUAL);
        verifyNoInteractions(prices);verify(attempts).save(any());
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertThat(listing.getConsecutiveFailures()).isEqualTo(1);
    }
    @Test void invalidSuccessCannotAppendPriceOrReplaceVerificationTimestamp() {
        ProductSnapshot bad=new ProductSnapshot(new BigDecimal("3999"),new BigDecimal("1609"),"INR","USB C Hub",null,true,"browseract");
        var result=new ScrapeResult(ScrapeOutcome.SUCCESS,bad,"amazon.in","url",null,2,0,FetchedPage.FetchSource.BROWSERACT,"browseract",null);
        assertThat(writer.recordSuccess(2L,result,TriggerSource.MANUAL).isSuccess()).isFalse();
        verifyNoInteractions(prices);assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
    }
    @Test void verifiedSuccessAppendsSeparatePriceAndMrp() {
        ProductSnapshot good=new ProductSnapshot(new BigDecimal("1599"),new BigDecimal("3999"),"INR","USB C Hub",null,true,"css");
        var result=new ScrapeResult(ScrapeOutcome.SUCCESS,good,"amazon.in","url",200,2,100,FetchedPage.FetchSource.DIRECT,"css",null);
        assertThat(writer.recordSuccess(2L,result,TriggerSource.MANUAL).isSuccess()).isTrue();
        ArgumentCaptor<PricePoint> point=ArgumentCaptor.forClass(PricePoint.class);verify(prices).save(point.capture());
        assertThat(point.getValue().getPrice()).isEqualByComparingTo("1599");
        assertThat(point.getValue().getListPrice()).isEqualByComparingTo("3999");
        assertThat(listing.getLastSuccessAt()).isAfter(verifiedAt);
        verify(prices,never()).delete(any());
    }
}
