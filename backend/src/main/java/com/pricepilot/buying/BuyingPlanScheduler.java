package com.pricepilot.buying;

import com.pricepilot.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Reuses saved evidence; the existing scheduler alone controls live price refreshes. */
@Component
@ConditionalOnProperty(prefix = "pricepilot.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BuyingPlanScheduler {
    private static final Logger log = LoggerFactory.getLogger(BuyingPlanScheduler.class);
    private final BuyingPlanStore store;
    private final BuyingPlanService plans;
    private final PlanningProperties properties;
    public BuyingPlanScheduler(BuyingPlanStore store, BuyingPlanService plans, PlanningProperties properties) {
        this.store = store; this.plans = plans; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pricepilot.planning.scheduler-delay-ms:60000}", initialDelayString = "${pricepilot.planning.scheduler-delay-ms:60000}")
    public void tick() {
        Instant now = Instant.now();
        for (BuyingPlanStore.Stored plan : store.due(now, properties.getSchedulerBatchSize())) {
            try { plans.reassess(plan, now); }
            catch (ApiException ex) { log.debug("Buying plan {} deferred: {}", plan.id(), ex.getCode()); }
            catch (RuntimeException ex) { log.warn("Buying plan {} could not be reassessed ({})", plan.id(), ex.getClass().getSimpleName()); }
        }
        store.publishPending(now, properties.getSchedulerBatchSize());
    }
}
