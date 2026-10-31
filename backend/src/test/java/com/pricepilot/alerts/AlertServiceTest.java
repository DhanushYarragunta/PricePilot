package com.pricepilot.alerts;

import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.domain.DealVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;

import static org.mockito.Mockito.*;

class AlertServiceTest {
    @Test void rejectedAsyncQueueRecordsFailureForAlreadyPersistedAlerts() {
        var writer = mock(AlertWriter.class);
        var mailer = mock(AlertMailer.class);
        var analysis = mock(DealAnalysis.class);
        when(analysis.getVerdict()).thenReturn(DealVerdict.STRONG_BUY);
        var pending = new PendingAlert(1L, "account@example.com", "Shopper", null, 2L,
                "Product", null, null, null, null, "Recorded alert");
        when(writer.fanOutDealAlerts(2L, analysis)).thenReturn(List.of(pending));
        doThrow(new TaskRejectedException("private executor details")).when(mailer).deliver(any());
        new AlertService(writer, mailer).dealDetected(2L, analysis);
        verify(writer).markFailed(1L, "Email delivery queue is full. The alert remains in the app.");
        verify(mailer).deliver(List.of(pending));
    }
}
