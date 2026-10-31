package com.pricepilot.web;

import com.pricepilot.assistant.ShoppingAdvice;
import com.pricepilot.assistant.ShoppingAssistantService;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.ProductTrackingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ShoppingAssistantController {
    private final ProductTrackingService tracking;
    private final ShoppingAssistantService assistant;
    private final RateLimiter limiter;

    public ShoppingAssistantController(ProductTrackingService tracking,
                                       ShoppingAssistantService assistant, RateLimiter limiter) {
        this.tracking = tracking;
        this.assistant = assistant;
        this.limiter = limiter;
    }

    @PostMapping("/{productId}/assistant")
    public ShoppingAdvice.Response advise(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long productId,
                                         @Valid @RequestBody ShoppingAdvice.Request request) {
        tracking.requireTracking(caller.id(), productId);
        limiter.check(String.valueOf(caller.id()), "shopping-assistant", 10,
                "You have used your ten shopping conversations for this hour. Please try again later.");
        return assistant.advise(productId, request);
    }
}
