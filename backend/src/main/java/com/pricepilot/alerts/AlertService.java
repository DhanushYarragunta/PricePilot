package com.pricepilot.alerts;

import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.schedule.RefreshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The entry point for "something happened to a price — does anyone need to know?".
 *
 * <p>Thin on purpose. The decisions and the database writes live in {@link AlertWriter}, the
 * delivery in {@link AlertMailer}, and this class exists to put them in the right order and to be
 * the one name the rest of the application depends on. Callers — the scheduler, the manual refresh
 * endpoint, the analysis hook — should not have to know that raising an alert is two transactions
 * and a background send.
 *
 * <p>The order is: record first, deliver second. The alert feed in the app is updated the moment
 * this returns, and the email follows on a background thread whenever SMTP gets round to it.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertWriter writer;
    private final AlertMailer mailer;

    public AlertService(AlertWriter writer, AlertMailer mailer) {
        this.writer = writer;
        this.mailer = mailer;
    }

    /** True when alerts will also go out by email, as opposed to only appearing in the app. */
    public boolean emailEnabled() {
        return mailer.isEnabled();
    }

    /**
     * Considers a freshly stored observation and alerts whoever should hear about it.
     *
     * <p>Cheap to call on every successful check: {@link RefreshResult#worthAlerting()} rejects the
     * large majority — checks that found the same price as last time — before any query runs.
     */
    public void evaluate(RefreshResult refresh) {
        if (refresh == null || !refresh.isSuccess() || refresh.productId() == null) {
            return;
        }
        if (!refresh.worthAlerting()) {
            return;
        }
        List<PendingAlert> pending = writer.fanOutPriceAlerts(refresh);
        dispatch(pending);
    }

    /**
     * Alerts watchers that Claude now rates this product worth buying.
     *
     * <p>Called after an analysis completes rather than from inside the analyser, so that the cost
     * and the notification stay separable: the verdict is stored regardless, and only a favourable
     * one reaches anybody's inbox.
     */
    public void dealDetected(Long productId, DealAnalysis analysis) {
        if (productId == null || analysis == null || analysis.getVerdict() == null) {
            return;
        }
        if (!analysis.getVerdict().isFavourable()) {
            return;
        }
        List<PendingAlert> pending = writer.fanOutDealAlerts(productId, analysis);
        if (!pending.isEmpty()) {
            log.info("Claude rated product {} as {}; notified {} watcher(s)",
                    productId, analysis.getVerdict(), pending.size());
            dispatch(pending);
        }
    }

    private void dispatch(List<PendingAlert> pending) {
        if (pending.isEmpty()) return;
        try { mailer.deliver(pending); }
        catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Email queue is full; {} alerts remain recorded", pending.size());
            for (PendingAlert alert : pending) {
                try { writer.markFailed(alert.alertLogId(), "Email delivery queue is full. The alert remains in the app."); }
                catch (RuntimeException failure) { log.error("Could not record email queue failure for alert {}", alert.alertLogId()); }
            }
        }
    }
}
