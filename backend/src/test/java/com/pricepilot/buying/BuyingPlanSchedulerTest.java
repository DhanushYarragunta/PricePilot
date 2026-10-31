package com.pricepilot.buying;

import com.pricepilot.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuyingPlanSchedulerTest {
    @Test void boundedBatchContinuesAfterAConcurrentEditAndPublishesPendingNotifications() {
        BuyingPlanStore store = mock(BuyingPlanStore.class);
        BuyingPlanService plans = mock(BuyingPlanService.class);
        PlanningProperties properties = new PlanningProperties(); properties.setSchedulerBatchSize(2);
        var first = new BuyingPlanStore.Stored(1L, 1L, 7L, 10L, 1, null, null, null, null, null);
        var second = new BuyingPlanStore.Stored(2L, 2L, 8L, 11L, 1, null, null, null, null, null);
        when(store.due(any(), eq(2))).thenReturn(List.of(first, second));
        doThrow(ApiException.conflict("buying_plan_changed", "Concurrent edit")).when(plans).reassess(eq(first), any());
        new BuyingPlanScheduler(store, plans, properties).tick();
        verify(plans).reassess(eq(first), any());
        verify(plans).reassess(eq(second), any());
        verify(store).publishPending(any(), eq(2));
        verifyNoMoreInteractions(plans);
    }
}
