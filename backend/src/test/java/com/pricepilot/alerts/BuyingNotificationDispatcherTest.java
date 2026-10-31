package com.pricepilot.alerts;

import com.pricepilot.buying.BuyingPlanNotificationEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BuyingNotificationDispatcherTest {
    @Test void slowSmtpDoesNotBlockPlanRequestsAndOverflowRemainsRetryable() throws Exception {
        var listener = mock(BuyingPlanNotificationListener.class);
        var event = new BuyingPlanNotificationEvent(1L, 2L, 3L, 4L, 5L,
                "TARGET_REACHED", "Test", "Test", Instant.now());
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return null; })
                .when(listener).onNotification(any());
        var dispatcher = new BuyingNotificationDispatcher(listener);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                dispatcher.onNotification(event);
                dispatcher.onNotification(event);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 32; i++) dispatcher.onNotification(event);
            assertThatThrownBy(() -> dispatcher.onNotification(event)).isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }
}
