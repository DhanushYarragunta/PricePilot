package com.pricepilot.alerts;

import com.pricepilot.buying.BuyingPlanNotificationEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded SMTP delivery without changing the existing application's async executor. */
@Component
public class BuyingNotificationDispatcher {
    private final BuyingPlanNotificationListener listener;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "buying-mail");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public BuyingNotificationDispatcher(BuyingPlanNotificationListener listener) {
        this.listener = listener;
        workers.allowCoreThreadTimeOut(true);
    }

    @EventListener
    public void onNotification(BuyingPlanNotificationEvent event) {
        // Rejection propagates to the store's publisher; the durable row remains pending.
        workers.execute(() -> listener.onNotification(event));
    }

    @PreDestroy
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
