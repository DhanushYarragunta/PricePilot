package com.pricepilot.schedule;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.alerts.AlertService;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.*;
import com.pricepilot.repo.*;
import com.pricepilot.scrape.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshWriterTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private static final String TITLE = "USB C Hub";
    private static final String IMAGE = "https://images.example/hub.jpg";
    private final ListingRepository listings=mock(ListingRepository.class);
    private final PricePointRepository prices=mock(PricePointRepository.class);
    private final ScrapeAttemptRepository attempts=mock(ScrapeAttemptRepository.class);
    private final ScrapeEngine engine = mock(ScrapeEngine.class);
    private final AlertService alerts = mock(AlertService.class);
    private final DealAnalyzer analyzer = mock(DealAnalyzer.class);
    private final AppProperties properties = new AppProperties();
    private final RefreshWriter writer = new RefreshWriter(listings, prices, attempts, new IntervalPolicy(properties));
    private final ListingRefreshService refreshes = new ListingRefreshService(
            listings, engine, writer, alerts, analyzer, properties);
    private Listing listing;
    private PricePoint previous;
    private Instant verifiedAt;
    @BeforeEach void setup() {
        Product product=new Product();product.setId(1L);product.setTitle(TITLE);product.setImageUrl(IMAGE);
        listing=new Listing();listing.setId(2L);listing.setProduct(product);listing.setRetailer("Amazon");
        listing.setUrl(URL);listing.setTitle(TITLE);listing.setLastStrategy("css");
        listing.setCheckIntervalMinutes(180);listing.setStatus(ListingStatus.ACTIVE);
        verifiedAt=Instant.now().minusSeconds(3600);listing.setLastSuccessAt(verifiedAt);
        listing.setLastCheckedAt(verifiedAt);listing.setNextCheckAt(verifiedAt);
        when(listings.findWithProductById(2L)).thenReturn(Optional.of(listing));
        when(listings.findWithProductByIdForUpdate(2L)).thenReturn(Optional.of(listing));
        previous = new PricePoint();
        previous.setId(3L);previous.setListing(listing);
        previous.setPrice(new BigDecimal("1609"));previous.setListPrice(new BigDecimal("3999"));
        previous.setCurrency("INR");previous.setInStock(true);
        previous.setSourceStrategy("css");previous.setCapturedAt(verifiedAt);
        when(prices.findFirstByListingIdOrderByCapturedAtDesc(2L)).thenReturn(Optional.of(previous));
    }
    @Test void failurePreservesHistoryAndVerificationTimestamp() {
        var result=ScrapeResult.aborted(ScrapeOutcome.TIMEOUT,"amazon.in","https://amazon.in/dp/B07CR28HLL",null,1000,"Timed out");
        writer.recordFailure(2L,result,TriggerSource.MANUAL);
        verifyNoInteractions(prices);verify(attempts).save(any());
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertThat(listing.getConsecutiveFailures()).isEqualTo(1);
    }
    @Test void invalidSuccessCannotAppendPriceOrReplaceVerificationTimestamp() {
        ProductSnapshot bad = new ProductSnapshot(new BigDecimal("3999"), new BigDecimal("1609"),
                "INR", "Unexpected replacement title", "https://images.example/wrong.jpg", false, "browseract");
        var result = new ScrapeResult(ScrapeOutcome.SUCCESS, bad, "amazon.in", URL, null,
                2, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", null);
        when(engine.scrapeRaw(eq(URL), any(Duration.class))).thenReturn(result);

        RefreshResult refresh = refreshes.refresh(2L, TriggerSource.MANUAL);

        assertThat(refresh.outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(refresh.isSuccess()).isFalse();
        verifyNoInteractions(prices, alerts, analyzer);
        assertPreviousObservationUnchanged();
        assertThat(listing.getTitle()).isEqualTo(TITLE);
        assertThat(listing.getProduct().getTitle()).isEqualTo(TITLE);
        assertThat(listing.getProduct().getImageUrl()).isEqualTo(IMAGE);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        ArgumentCaptor<ScrapeAttempt> captured = ArgumentCaptor.forClass(ScrapeAttempt.class);
        verify(attempts).save(captured.capture());
        assertThat(captured.getValue().getOutcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(captured.getValue().getErrorMessage()).contains("previous price preserved");
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

    @ParameterizedTest(name = "{0} via {4}")
    @MethodSource("browserActFailures")
    void browserActFailurePreservesLastOfferAndRecordsAttemptWithoutAlerts(
            String scenario, ScrapeOutcome outcome, Integer httpStatus, String diagnosis, TriggerSource trigger) {
        ScrapeResult result = new ScrapeResult(outcome, null, "amazon.in", URL, httpStatus,
                1234, 120, FetchedPage.FetchSource.BROWSERACT, "browseract", diagnosis);
        when(engine.scrapeRaw(eq(URL), any(Duration.class))).thenReturn(result);
        Instant startedAt = Instant.now();

        RefreshResult refresh = refreshes.refresh(2L, trigger);

        verify(engine).scrapeRaw(eq(URL), any(Duration.class));
        verifyNoInteractions(prices, alerts, analyzer);
        assertPreviousObservationUnchanged();
        assertThat(listing.getTitle()).isEqualTo(TITLE);
        assertThat(listing.getProduct().getTitle()).isEqualTo(TITLE);
        assertThat(listing.getProduct().getImageUrl()).isEqualTo(IMAGE);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertThat(listing.getLastStrategy()).isEqualTo("css");
        assertThat(listing.getLastCheckedAt()).isBetween(startedAt, Instant.now());
        assertThat(listing.getConsecutiveFailures()).isEqualTo(1);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getCheckIntervalMinutes()).isGreaterThan(180);
        assertThat(listing.getNextCheckAt()).isAfter(startedAt);

        assertThat(refresh.outcome()).isEqualTo(outcome);
        assertThat(refresh.isSuccess()).isFalse();
        assertThat(refresh.price()).isNull();
        assertThat(refresh.previousPrice()).isNull();
        assertThat(refresh.mrp()).isNull();
        assertThat(refresh.priceChanged()).isFalse();
        assertThat(refresh.cameBackInStock()).isFalse();
        assertThat(refresh.allTimeLow()).isFalse();
        assertThat(refresh.worthAlerting()).isFalse();
        assertThat(refresh.paid()).isTrue();
        assertThat(refresh.consumedRequest()).isTrue();
        assertThat(refresh.message()).isEqualTo(diagnosis);
        assertThat(refresh.nextCheckAt()).isEqualTo(listing.getNextCheckAt());
        assertThat(refresh.intervalMinutes()).isEqualTo(listing.getCheckIntervalMinutes());

        ArgumentCaptor<ScrapeAttempt> captured = ArgumentCaptor.forClass(ScrapeAttempt.class);
        verify(attempts).save(captured.capture());
        verifyNoMoreInteractions(attempts);
        ScrapeAttempt attempt = captured.getValue();
        assertThat(attempt.getListing()).isSameAs(listing);
        assertThat(attempt.getHost()).isEqualTo("amazon.in");
        assertThat(attempt.getStrategy()).isEqualTo("browseract");
        assertThat(attempt.getOutcome()).isEqualTo(outcome);
        assertThat(attempt.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(attempt.getLatencyMs()).isEqualTo(1234);
        assertThat(attempt.getBytes()).isEqualTo(120);
        assertThat(attempt.getErrorMessage()).isEqualTo(diagnosis);
        assertThat(attempt.getTriggerSource()).isEqualTo(trigger);
    }

    private static Stream<Arguments> browserActFailures() {
        return Stream.of(
                new Failure("authentication rejected", ScrapeOutcome.ERROR, 401, "BrowserAct authentication failed"),
                new Failure("access denied", ScrapeOutcome.ERROR, 403, "BrowserAct access denied"),
                new Failure("provider HTTP failure", ScrapeOutcome.ERROR, 503, "BrowserAct unavailable"),
                new Failure("timeout", ScrapeOutcome.TIMEOUT, null, "BrowserAct task timed out"),
                new Failure("malformed extraction", ScrapeOutcome.NO_PRICE, 200, "BrowserAct returned invalid product data"),
                new Failure("product mismatch", ScrapeOutcome.NO_PRICE, 200, "BrowserAct result did not match the requested product")
        ).flatMap(failure -> Stream.of(TriggerSource.SCHEDULER, TriggerSource.MANUAL)
                .map(trigger -> Arguments.of(failure.name(), failure.outcome(), failure.httpStatus(), failure.message(), trigger)));
    }

    private record Failure(String name, ScrapeOutcome outcome, Integer httpStatus, String message) { }

    @Test void verifiedBrowserActSuccessAppendsHistoryAndKeepsSchedulingAndAlertFollowUps() {
        listing.setStatus(ListingStatus.BLOCKED);
        listing.setConsecutiveFailures(3);
        when(prices.allTimeLow(1L)).thenReturn(Optional.of(previous.getPrice()));
        when(analyzer.analyseIfDue(1L)).thenReturn(Optional.empty());
        ProductSnapshot snapshot = new ProductSnapshot(new BigDecimal("1599"), new BigDecimal("3999"),
                "INR", "USB C Hub with Ethernet", "https://images.example/new-hub.jpg", true, "browseract");
        ScrapeResult result = new ScrapeResult(ScrapeOutcome.SUCCESS, snapshot, "amazon.in", URL, null,
                900, 250, FetchedPage.FetchSource.BROWSERACT, "browseract", null);
        when(engine.scrapeRaw(eq(URL), any(Duration.class))).thenReturn(result);
        Instant startedAt = Instant.now();

        RefreshResult refresh = refreshes.refresh(2L, TriggerSource.MANUAL);

        assertThat(refresh.isSuccess()).isTrue();
        assertThat(refresh.paid()).isTrue();
        assertThat(refresh.price()).isEqualByComparingTo("1599");
        assertThat(refresh.previousPrice()).isEqualByComparingTo("1609");
        assertThat(refresh.mrp()).isEqualByComparingTo("3999");
        assertThat(refresh.priceChanged()).isTrue();
        assertThat(refresh.allTimeLow()).isTrue();
        assertThat(refresh.worthAlerting()).isTrue();

        ArgumentCaptor<PricePoint> captured = ArgumentCaptor.forClass(PricePoint.class);
        var writesBeforeAlerts = inOrder(prices, attempts, alerts);
        writesBeforeAlerts.verify(prices).allTimeLow(1L);
        writesBeforeAlerts.verify(prices).save(captured.capture());
        writesBeforeAlerts.verify(attempts).save(any());
        writesBeforeAlerts.verify(alerts).evaluate(refresh);
        verify(analyzer).analyseIfDue(1L);
        PricePoint point = captured.getValue();
        assertThat(point).isNotSameAs(previous);
        assertThat(point.getListing()).isSameAs(listing);
        assertThat(point.getPrice()).isEqualByComparingTo("1599");
        assertThat(point.getListPrice()).isEqualByComparingTo("3999");
        assertThat(point.getCurrency()).isEqualTo("INR");
        assertThat(point.isInStock()).isTrue();
        assertThat(point.getCapturedAt()).isEqualTo(result.observedAt());
        assertThat(point.getSourceStrategy()).isEqualTo("browseract");
        assertThat(point.isChanged()).isTrue();
        assertPreviousObservationUnchanged();
        verify(prices, never()).delete(any());

        assertThat(listing.getTitle()).isEqualTo(snapshot.title());
        assertThat(listing.getProduct().getTitle()).isEqualTo(TITLE);
        assertThat(listing.getProduct().getImageUrl()).isEqualTo(IMAGE);
        assertThat(listing.getLastSuccessAt()).isEqualTo(result.observedAt());
        assertThat(listing.getLastStrategy()).isEqualTo("browseract");
        assertThat(listing.getConsecutiveFailures()).isZero();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getCheckIntervalMinutes()).isLessThan(180);
        assertThat(listing.getNextCheckAt()).isAfter(startedAt);

        ArgumentCaptor<ScrapeAttempt> attempt = ArgumentCaptor.forClass(ScrapeAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getOutcome()).isEqualTo(ScrapeOutcome.SUCCESS);
        assertThat(attempt.getValue().getStrategy()).isEqualTo("browseract");
        assertThat(attempt.getValue().getHttpStatus()).isNull();
        assertThat(attempt.getValue().getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
    }

    private void assertPreviousObservationUnchanged() {
        assertThat(previous.getPrice()).isEqualByComparingTo("1609");
        assertThat(previous.getListPrice()).isEqualByComparingTo("3999");
        assertThat(previous.getCurrency()).isEqualTo("INR");
        assertThat(previous.isInStock()).isTrue();
        assertThat(previous.getCapturedAt()).isEqualTo(verifiedAt);
        assertThat(previous.getSourceStrategy()).isEqualTo("css");
    }

    @Test void reusedObservationKeepsItsOriginalTimestampAndCannotAppendDuplicateHistory() {
        Instant observed = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ProductSnapshot snapshot = new ProductSnapshot(new BigDecimal("1599"), new BigDecimal("3999"),
                "INR", TITLE, IMAGE, true, "browseract");
        ScrapeResult reused = new ScrapeResult(ScrapeOutcome.SUCCESS, snapshot, "amazon.in", URL, null,
                0, 0, null, "browseract", null, observed, ScrapeResult.Reuse.CACHE);

        RefreshResult first = writer.recordSuccess(2L, reused, TriggerSource.MANUAL);
        assertThat(first.isSuccess()).isTrue();
        assertThat(first.consumedRequest()).isFalse();
        ArgumentCaptor<PricePoint> captured = ArgumentCaptor.forClass(PricePoint.class);
        verify(prices).save(captured.capture());
        assertThat(captured.getValue().getCapturedAt()).isEqualTo(observed);
        assertThat(listing.getLastSuccessAt()).isEqualTo(observed);
        assertThat(listing.getLastCheckedAt()).isEqualTo(observed);
        assertThat(first.nextCheckAt()).isBetween(observed.plusSeconds(first.intervalMinutes() * 60L),
                observed.plusSeconds((long) (first.intervalMinutes() * 60L * 1.15)));
        Instant nextCheck = listing.getNextCheckAt();
        when(prices.findFirstByListingIdOrderByCapturedAtDesc(2L)).thenReturn(Optional.of(captured.getValue()));

        RefreshResult replay = writer.recordSuccess(2L, reused.reusedAs(ScrapeResult.Reuse.SHARED), TriggerSource.SCHEDULER);
        assertThat(replay.isSkipped()).isTrue();
        assertThat(replay.priceChanged()).isFalse();
        assertThat(replay.worthAlerting()).isFalse();
        assertThat(listing.getLastSuccessAt()).isEqualTo(observed);
        assertThat(listing.getNextCheckAt()).isEqualTo(nextCheck);
        verify(prices, times(1)).save(any());
        verify(attempts, times(1)).save(any());
        verify(listings, times(2)).findWithProductByIdForUpdate(2L);
    }

    @Test void olderObservationCannotOverwriteANewerValidPrice() {
        ScrapeResult older = new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1299"), null, "INR", TITLE, IMAGE, true, "browseract"),
                "amazon.in", URL, null, 0, 0, null, "browseract", null,
                verifiedAt.minusSeconds(60), ScrapeResult.Reuse.CACHE);
        assertThat(writer.recordSuccess(2L, older, TriggerSource.MANUAL).isSkipped()).isTrue();
        verify(prices, never()).save(any());
        verifyNoInteractions(attempts);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertPreviousObservationUnchanged();
    }

    @Test void postgresMicrosecondPrecisionCannotTurnTheSameObservationIntoANewPoint() {
        Instant databaseTime = Instant.parse("2026-01-01T01:00:00.123456Z");
        listing.setLastSuccessAt(databaseTime);
        previous.setCapturedAt(databaseTime);
        ScrapeResult originalNanos = new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1609"), null, "INR", TITLE, IMAGE, true, "browseract"),
                "amazon.in", URL, null, 0, 0, null, "browseract", null,
                databaseTime.plusNanos(789), ScrapeResult.Reuse.CACHE);
        assertThat(writer.recordSuccess(2L, originalNanos, TriggerSource.MANUAL).isSkipped()).isTrue();
        verify(prices, never()).save(any());
        verifyNoInteractions(attempts);
        assertThat(listing.getLastSuccessAt()).isEqualTo(databaseTime);
    }

    @Test void cachedObservationCannotEraseTheStateOfAMoreRecentFailure() {
        Instant failedAt = Instant.now().minusSeconds(20);
        listing.setLastCheckedAt(failedAt);
        listing.setConsecutiveFailures(8);
        listing.setStatus(ListingStatus.BLOCKED);
        Instant nextCheck = listing.getNextCheckAt();
        ScrapeResult older = new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1599"), null, "INR", TITLE, IMAGE, true, "browseract"),
                "amazon.in", URL, null, 0, 0, null, "browseract", null,
                failedAt.minusSeconds(10), ScrapeResult.Reuse.CACHE);
        assertThat(writer.recordSuccess(2L, older, TriggerSource.MANUAL).isSkipped()).isTrue();
        assertThat(listing.getLastCheckedAt()).isEqualTo(failedAt);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.BLOCKED);
        assertThat(listing.getConsecutiveFailures()).isEqualTo(8);
        assertThat(listing.getNextCheckAt()).isEqualTo(nextCheck);
        verify(prices, never()).save(any());
        verifyNoInteractions(attempts);
    }

    @Test void configurationFailureBeforeSubmissionIsNotAnOutboundRequest() {
        ScrapeResult unsubmitted = ScrapeResult.aborted(ScrapeOutcome.ERROR, "amazon.in", URL,
                null, 0, "BrowserAct is not configured");
        RefreshResult failure = writer.recordFailure(2L, unsubmitted, TriggerSource.MANUAL);
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.consumedRequest()).isFalse();
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        verifyNoInteractions(prices);
    }

    @Test void futureObservationFailsWithoutRenewingFreshnessOrCreatingHistory() {
        ScrapeResult future = new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1299"), null, "INR", TITLE, IMAGE, true, "browseract"),
                "amazon.in", URL, null, 0, 0, null, "browseract", null,
                Instant.now().plusSeconds(600), ScrapeResult.Reuse.CACHE);
        assertThat(writer.recordSuccess(2L, future, TriggerSource.MANUAL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        verify(prices, never()).save(any());
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        assertPreviousObservationUnchanged();
    }

    @Test void repeatedManualCacheHitsNeverMoveTheExistingSchedule() {
        Instant next = Instant.now().plusSeconds(2400);
        listing.setNextCheckAt(next);
        ScrapeResult skip = ScrapeResult.skippedFresh("amazon.in", URL);
        writer.recordSkip(2L, skip, TriggerSource.MANUAL);
        writer.recordSkip(2L, skip, TriggerSource.MANUAL);
        assertThat(listing.getNextCheckAt()).isEqualTo(next);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        verify(prices, never()).save(any());
    }

    @Test void dueCacheSkipReschedulesFromObservationInsteadOfTimeOfRepeatedReads() {
        Instant expected = verifiedAt.plusSeconds(180 * 60L);
        writer.recordSkip(2L, ScrapeResult.skippedFresh("amazon.in", URL), TriggerSource.SCHEDULER);
        assertThat(listing.getNextCheckAt()).isEqualTo(expected);
        writer.recordSkip(2L, ScrapeResult.skippedFresh("amazon.in", URL), TriggerSource.SCHEDULER);
        assertThat(listing.getNextCheckAt()).isEqualTo(expected);
        assertThat(listing.getLastSuccessAt()).isEqualTo(verifiedAt);
        verify(prices, never()).save(any());
    }
}
