package com.pricepilot.buying;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pricepilot.ai.AiClient;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.service.ProductTrackingService;
import com.pricepilot.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.pricepilot.buying.BuyingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuyingPlanServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ProductTrackingService tracking = mock(ProductTrackingService.class);
    private final BuyingPlanStore store = mock(BuyingPlanStore.class);
    private final BuyingEvidenceReader reader = mock(BuyingEvidenceReader.class);
    private final PlanningProperties properties = new PlanningProperties();
    private final BuyingHistory history = new BuyingHistory(properties);
    private final AiClient ai = mock(AiClient.class);
    private final BuyingConversationParser parser = new BuyingConversationParser(ai, mapper, properties);
    private final AtomicReference<BuyingPlanStore.Stored> state = new AtomicReference<>();
    private final List<Conversation> turns = new ArrayList<>();
    private BuyingPlanService service;

    @BeforeEach void setup() {
        Instant now = Instant.now();
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        List<PricePoint> points = new ArrayList<>();
        for (int index = 19; index >= 0; index--) points.add(BuyingFixtures.point(listing, "1000", now.minus(Duration.ofDays(index))));
        History evidence = history.analyze(List.of(listing), points, List.of(points.get(19)), 10L, now);
        when(reader.read(eq(7L), nullable(Long.class), any())).thenReturn(evidence);
        state.set(new BuyingPlanStore.Stored(55L, 1L, 7L, 10L, 0, mapper.valueToTree(Preferences.defaults(10L)),
                mapper.createObjectNode(), null, null, now));
        when(store.find(1L, 7L)).thenAnswer(invocation -> Optional.of(state.get()));
        when(store.conversation(55L)).thenAnswer(invocation -> List.copyOf(turns));
        when(store.notifications(55L)).thenReturn(List.of());
        when(store.save(any(), any(), any(), nullable(String.class), nullable(String.class), nullable(BuyingPlanStore.Notice.class), any()))
                .thenAnswer(invocation -> {
                    BuyingPlanStore.Stored previous = invocation.getArgument(0);
                    Preferences prefs = invocation.getArgument(1);
                    Snapshot snapshot = invocation.getArgument(2);
                    Instant savedAt = invocation.getArgument(6);
                    if (previous.version() != state.get().version()) throw ApiException.conflict("buying_plan_changed", "Reload");
                    String user = invocation.getArgument(3), assistant = invocation.getArgument(4);
                    if (user != null) turns.add(new Conversation((long) turns.size() + 1, "user", user, savedAt));
                    if (assistant != null) turns.add(new Conversation((long) turns.size() + 1, "assistant", assistant, savedAt));
                    var saved = new BuyingPlanStore.Stored(55L, 1L, 7L, prefs.listingId(), previous.version() + 1,
                            mapper.valueToTree(prefs), mapper.valueToTree(snapshot), snapshot.plan().reassessAt(), snapshot.observedAt(), savedAt);
                    state.set(saved);
                    return saved;
                });
        service = service();
    }

    @AfterEach void close() { parser.close(); }

    @Test void initialGetDoesNotEmailAnInferredTargetBeforePreferencesExist() {
        View view = service.get(1L, 7L);
        assertThat(view.plan().outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(view.followUpQuestion()).contains("maximum budget");
        verify(store).save(any(), any(), any(), isNull(), isNull(), isNull(), any());
        verify(ai, never()).complete(anyString(), anyString(), any());
    }

    @Test void initialGetConflictReturnsTheCommittedPlanAndItsSelectedListingHistory() throws Exception {
        BuyingPlanStore.Stored initial = state.get();
        View first = service.get(1L, 7L);
        Instant observedAt = Instant.now();
        var otherListing = BuyingFixtures.listing(20, "Flipkart");
        var otherPoint = BuyingFixtures.point(otherListing, "850", observedAt);
        History otherHistory = history.analyze(List.of(otherListing), List.of(otherPoint), List.of(otherPoint), 20L, observedAt);
        when(reader.read(eq(7L), eq(20L), any())).thenReturn(otherHistory);
        View committed = service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"listingId\":20,\"budget\":900,\"targetPrice\":850,\"deadlineKind\":\"FLEXIBLE\"}")));
        when(store.find(1L, 7L)).thenReturn(Optional.of(initial), Optional.of(state.get()));
        clearInvocations(store, reader);

        View reloaded = service.get(1L, 7L);

        assertThat(reloaded.version()).isEqualTo(committed.version());
        assertThat(reloaded.preferences()).isEqualTo(committed.preferences());
        assertThat(reloaded.plan()).isEqualTo(committed.plan());
        assertThat(reloaded.history()).isEqualTo(otherHistory);
        assertThat(reloaded.history().selectedListingId()).isEqualTo(20L);
        verify(store, times(2)).find(1L, 7L);
        verify(store).save(any(), any(), any(), isNull(), isNull(), isNull(), any());
        verify(reader).read(eq(7L), eq(10L), any());
        verify(reader).read(eq(7L), eq(20L), any());
    }

    @Test void getDoesNotHideUnrelatedSaveErrors() {
        ApiException unavailable = ApiException.unavailable("Buying-plan storage is temporarily unavailable");
        doThrow(unavailable).when(store).save(any(), any(), any(), isNull(), isNull(), isNull(), any());

        assertThatThrownBy(() -> service.get(1L, 7L)).isSameAs(unavailable);

        verify(store).find(1L, 7L);
    }

    @Test void conversationAndCorrectionsSurviveReloadWithOneCanonicalDeadlineField() {
        View initial = service.get(1L, 7L);
        LocalDate deadline = LocalDate.now(ZoneId.of(properties.getZoneId())).plusMonths(3);
        View specified = service.converse(1L, 7L, new MessageRequest(initial.version(),
                "My absolute budget is ₹1200. I need to buy by " + deadline + "."));
        assertThat(specified.preferences().budget()).isEqualByComparingTo("1200");
        assertThat(specified.preferences().deadlineDate()).isEqualTo(deadline);
        assertThat(specified.followUpQuestion()).isNull();
        View corrected = service.converse(1L, 7L, new MessageRequest(specified.version(), "Actually I can wait until May."));
        View reloaded = service().get(1L, 7L);
        assertThat(reloaded.preferences().deadlineDate()).isNull();
        assertThat(reloaded.preferences().deadlineKind()).isEqualTo(DeadlineKind.FLEXIBLE);
        assertThat(reloaded.preferences().budget()).isEqualByComparingTo("1200");
        assertThat(reloaded.conversation()).hasSize(4);
        assertThat(reloaded.conversation()).extracting(Conversation::content).contains("Actually I can wait until May.");
        assertThat(reloaded.followUpQuestion()).isEqualTo(corrected.followUpQuestion());
        assertThat(reloaded.version()).isEqualTo(corrected.version());
    }

    @Test void staleVersionCannotOverwriteNewPreferences() throws Exception {
        View first = service.get(1L, 7L);
        service.patch(1L, 7L, new PatchRequest(first.version(), mapper.readTree("{\"budget\":1200}")));
        assertThatThrownBy(() -> service.patch(1L, 7L, new PatchRequest(first.version(), mapper.createObjectNode().put("budget", 900))))
                .isInstanceOf(ApiException.class).hasMessageContaining("changed");
        assertThat(state.get().preferences().path("budget").decimalValue()).isEqualByComparingTo("1200");
    }

    @Test void ownershipIsCheckedBeforeReadingPrivatePlansOrPriceHistory() {
        doThrow(ApiException.notYours("Product")).when(tracking).requireTracking(2L, 7L);
        assertThatThrownBy(() -> service.get(2L, 7L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(store, reader);
    }

    @Test void explicitTargetTriggersOneStableDedupKeyAndScheduledEvaluationUsesNoAi() throws Exception {
        View first = service.get(1L, 7L);
        service.patch(1L, 7L, new PatchRequest(first.version(), mapper.readTree("{\"budget\":1200,\"targetPrice\":1000,\"deadlineKind\":\"FLEXIBLE\"}")));
        service.reassess(state.get(), Instant.now().plusSeconds(30));
        ArgumentCaptor<BuyingPlanStore.Notice> notices = ArgumentCaptor.forClass(BuyingPlanStore.Notice.class);
        verify(store, times(3)).save(any(), any(), any(), nullable(String.class), nullable(String.class), notices.capture(), any());
        var emitted = notices.getAllValues().stream().filter(java.util.Objects::nonNull).toList();
        assertThat(emitted).hasSize(2);
        assertThat(emitted).allSatisfy(notice -> assertThat(notice.kind()).isEqualTo("TARGET_REACHED"));
        assertThat(emitted.get(0).dedupKey()).isEqualTo(emitted.get(1).dedupKey());
        verify(ai, never()).complete(anyString(), anyString(), any());
    }

    @Test void snapshotReassessmentIsCappedAtTheCurrentObservationsFreshnessExpiry() throws Exception {
        Instant now = Instant.now();
        Instant observedAt = now.minus(Duration.ofHours(23));
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        var point = BuyingFixtures.point(listing, "1000", observedAt);
        History data = history.analyze(List.of(listing), List.of(point), List.of(point), 10L, now);
        when(reader.read(eq(7L), nullable(Long.class), any())).thenReturn(data);
        View first = service.get(1L, 7L);
        View ready = service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"budget\":1200,\"targetPrice\":1000,\"deadlineKind\":\"FLEXIBLE\"}")));
        assertThat(ready.plan().outcome()).isEqualTo(Outcome.BUY_NOW);
        assertThat(ready.plan().reassessAt()).isEqualTo(observedAt.plus(Duration.ofHours(24)));
    }

    @Test void crossingFreshnessBoundaryInvalidatesBuyAdviceWithoutANewObservationTimestamp() throws Exception {
        Instant observedAt = Instant.now();
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        var point = BuyingFixtures.point(listing, "1000", observedAt);
        History fresh = history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt);
        when(reader.read(eq(7L), nullable(Long.class), any())).thenReturn(fresh);
        View first = service.get(1L, 7L);
        View ready = service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"budget\":1200,\"targetPrice\":1000,\"deadlineKind\":\"FLEXIBLE\"}")));
        assertThat(ready.plan().outcome()).isEqualTo(Outcome.BUY_NOW);
        History expired = history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt.plus(Duration.ofHours(25)));
        when(reader.read(eq(7L), nullable(Long.class), any())).thenReturn(expired);

        View afterExpiry = service.get(1L, 7L);

        assertThat(afterExpiry.plan().outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(afterExpiry.history().series().get(0).stats().lastObservedAt()).isEqualTo(observedAt);
        assertThat(afterExpiry.version()).isGreaterThan(ready.version());
    }

    @Test void retiredListingInvalidatesBuyAdviceWithUnchangedPriceAndTimestamp() throws Exception {
        Instant observedAt = Instant.now();
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        var point = BuyingFixtures.point(listing, "1000", observedAt);
        when(reader.read(eq(7L), nullable(Long.class), any()))
                .thenReturn(history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt));
        View first = service.get(1L, 7L);
        View ready = service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"budget\":1200,\"targetPrice\":1000,\"deadlineKind\":\"FLEXIBLE\"}")));
        assertThat(ready.plan().outcome()).isEqualTo(Outcome.BUY_NOW);
        listing.setStatus(com.pricepilot.domain.ListingStatus.DEAD);
        when(reader.read(eq(7L), nullable(Long.class), any()))
                .thenReturn(history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt));

        View retired = service.get(1L, 7L);

        assertThat(retired.plan().outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(retired.history().series().get(0).stats().lastObservedAt()).isEqualTo(observedAt);
    }

    @Test void newlyObservedPriceAboveTargetStopsQualifyingTargetNotices() throws Exception {
        View first = service.get(1L, 7L);
        service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"budget\":1200,\"targetPrice\":1000,\"deadlineKind\":\"FLEXIBLE\"}")));
        Instant observedAt = Instant.now();
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        var point = BuyingFixtures.point(listing, "1100", observedAt);
        when(reader.read(eq(7L), nullable(Long.class), any()))
                .thenReturn(history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt));
        clearInvocations(store);

        service.reassess(state.get(), observedAt);

        ArgumentCaptor<BuyingPlanStore.Notice> notice = ArgumentCaptor.forClass(BuyingPlanStore.Notice.class);
        verify(store).save(any(), any(), any(), isNull(), isNull(), notice.capture(), any());
        assertThat(notice.getValue() == null || !notice.getValue().kind().equals("TARGET_REACHED")).isTrue();
        assertThat(state.get().snapshot().path("plan").path("outcome").asText()).isEqualTo("BUY_AT_TARGET_PRICE");
    }

    @Test void desiredSavingsTargetIsAnchoredAndCanBeReachedAfterANewLowerObservation() throws Exception {
        View first = service.get(1L, 7L);
        View planned = service.patch(1L, 7L, new PatchRequest(first.version(),
                mapper.readTree("{\"budget\":1200,\"desiredSavingsPercent\":10,\"deadlineKind\":\"FLEXIBLE\"}")));
        assertThat(planned.plan().targetPrice()).isEqualByComparingTo("900");
        Instant observedAt = Instant.now();
        var listing = BuyingFixtures.listing(10, "Amazon.in");
        var point = BuyingFixtures.point(listing, "900", observedAt);
        when(reader.read(eq(7L), nullable(Long.class), any()))
                .thenReturn(history.analyze(List.of(listing), List.of(point), List.of(point), 10L, observedAt));

        View reached = service.get(1L, 7L);

        assertThat(reached.plan().targetPrice()).isEqualByComparingTo("900");
        assertThat(reached.plan().outcome()).isEqualTo(Outcome.BUY_NOW);
        assertThat(state.get().snapshot().path("savingsTarget").decimalValue()).isEqualByComparingTo("900");
    }

    private BuyingPlanService service() {
        return new BuyingPlanService(tracking, store, reader, history, new BuyingForecast(properties), new BuyingDecision(properties),
                new BuyingPreferences(mapper), parser, mapper, properties);
    }
}
