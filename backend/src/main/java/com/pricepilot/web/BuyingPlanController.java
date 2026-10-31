package com.pricepilot.web;

import com.pricepilot.buying.BuyingModels;
import com.pricepilot.buying.BuyingPlanService;
import com.pricepilot.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{productId}/buying-plan")
public class BuyingPlanController {
    private final BuyingPlanService plans;
    private final RateLimiter limiter;
    public BuyingPlanController(BuyingPlanService plans, RateLimiter limiter) { this.plans = plans; this.limiter = limiter; }

    @GetMapping
    public BuyingModels.View get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long productId) {
        return plans.get(user.id(), productId);
    }

    @PatchMapping
    public BuyingModels.View patch(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long productId,
                                   @Valid @RequestBody BuyingModels.PatchRequest request) {
        return plans.patch(user.id(), productId, request);
    }

    @PostMapping("/conversation")
    public BuyingModels.View converse(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long productId,
                                      @Valid @RequestBody BuyingModels.MessageRequest request) {
        limiter.check(String.valueOf(user.id()), "buying-plan-conversation", 20,
                "You have used your twenty buying-plan conversations for this hour. You can still edit your saved requirements.");
        return plans.converse(user.id(), productId, request);
    }
}
