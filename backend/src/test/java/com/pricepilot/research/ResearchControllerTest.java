package com.pricepilot.research;

import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.ProductTrackingService;
import com.pricepilot.web.ApiException;
import com.pricepilot.web.RateLimiter;
import com.pricepilot.web.ResearchController;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchControllerTest {
    @Test void requiresTrackedOwnershipBeforeProviderOrCacheAccess(){
        var tracking=mock(ProductTrackingService.class);var research=mock(ResearchService.class);var limiter=mock(RateLimiter.class);
        var controller=new ResearchController(tracking,research,limiter);var caller=new AuthenticatedUser(7L,"test@example.com","USER");
        doThrow(ApiException.notYours("Product")).when(tracking).requireTracking(7L,1L);
        assertThatThrownBy(()->controller.post(caller,1L,new ResearchReport.Request(null,null,null,false))).isInstanceOf(ApiException.class);
        verifyNoInteractions(research,limiter);
    }
    @Test void readsCacheWithoutChargingRequestRateButResearchIsLimited(){
        var tracking=mock(ProductTrackingService.class);var research=mock(ResearchService.class);var limiter=mock(RateLimiter.class);
        var controller=new ResearchController(tracking,research,limiter);var caller=new AuthenticatedUser(7L,"test@example.com","USER");
        controller.get(caller,1L,null,null,null);verifyNoInteractions(limiter);
        controller.post(caller,1L,new ResearchReport.Request(null,null,null,false));
        verify(limiter).check(eq("7"),eq("product-research"),eq(6),anyString());
    }
}
