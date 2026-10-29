package com.pricepilot.usage;

import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ApiUsage;
import com.pricepilot.repo.ApiUsageRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetGuardTest {
    private final ApiUsageRepository repo = mock(ApiUsageRepository.class);
    private final BudgetGuard guard = new BudgetGuard(repo);

    private ApiUsage usage(ApiProvider provider, int calls) {
        ApiUsage usage = new ApiUsage();
        usage.setProvider(provider);
        usage.setPeriod(BudgetGuard.currentPeriod());
        usage.setCalls(calls);
        when(repo.findForUpdate(eq(provider), anyString())).thenReturn(Optional.of(usage));
        return usage;
    }

    @Test void unlimitedBrowserActKeepsCountingWithoutEnforcingACap() {
        ApiUsage usage = usage(ApiProvider.BROWSERACT, 100000);
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, -1)).isTrue();
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, -1)).isTrue();
        assertThat(usage.getCalls()).isEqualTo(100002);
        verify(repo, times(2)).save(usage);
    }

    @Test void zeroAndUnsupportedNegativeLimitsStayDisabled() {
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, 0)).isFalse();
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, -2)).isFalse();
        assertThat(guard.tryConsumeCall(ApiProvider.TAVILY, -1)).isFalse();
        assertThat(guard.tryConsumeCall(ApiProvider.UNBLOCKER, -1)).isFalse();
        verifyNoInteractions(repo);
    }

    @Test void positiveCapStillStopsAtTheLimit() {
        ApiUsage usage = usage(ApiProvider.BROWSERACT, 9);
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, 10)).isTrue();
        assertThat(guard.tryConsumeCall(ApiProvider.BROWSERACT, 10)).isFalse();
        assertThat(usage.getCalls()).isEqualTo(10);
        verify(repo, times(1)).save(usage);
    }
}
