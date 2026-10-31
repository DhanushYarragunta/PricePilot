package com.pricepilot.web;

import com.pricepilot.research.ResearchReport;
import com.pricepilot.research.ResearchService;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.ProductTrackingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/{productId}/research")
public class ResearchController {
    private final ProductTrackingService tracking;
    private final ResearchService research;
    private final RateLimiter limiter;
    public ResearchController(ProductTrackingService tracking,ResearchService research,RateLimiter limiter){
        this.tracking=tracking;this.research=research;this.limiter=limiter;
    }
    @GetMapping public ResearchReport get(@AuthenticationPrincipal AuthenticatedUser caller,@PathVariable Long productId,
            @RequestParam(required=false) Long listingId,@RequestParam(required=false) String model,@RequestParam(required=false) String variant){
        tracking.requireTracking(caller.id(),productId);
        if((model!=null&&model.length()>160)||(variant!=null&&variant.length()>160))throw ApiException.badRequest("Model and variant must each be at most 160 characters.");
        return research.get(productId,new ResearchReport.Request(listingId,model,variant,false));
    }
    @PostMapping public ResearchReport post(@AuthenticationPrincipal AuthenticatedUser caller,@PathVariable Long productId,
            @Valid @RequestBody ResearchReport.Request request){
        tracking.requireTracking(caller.id(),productId);
        limiter.check(String.valueOf(caller.id()),"product-research",6,"You have used this hour's six research requests. Cached reports remain available.");
        return research.research(productId,request);
    }
}
