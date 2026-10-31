package com.pricepilot.scrape.browseract;

import com.pricepilot.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserActConfigurationTest {
    private final AppProperties.BrowserAct config = new AppProperties.BrowserAct();

    @Test void oneKeyAndWorkflowConfigureBrowserAct() {
        config.setApiKey("test-only-key");
        config.setWorkflowId("shared-product-workflow");

        assertThat(config.isConfigured()).isTrue();
        assertThat(config.getWorkflowId()).isEqualTo("shared-product-workflow");
    }

    @Test void missingWorkflowDoesNotConfigureBrowserActDespiteAKey() {
        config.setApiKey("test-only-key");

        assertThat(config.isConfigured()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void nullOrBlankWorkflowDoesNotConfigureBrowserActDespiteAKey(String workflowId) {
        config.setApiKey("test-only-key");
        config.setWorkflowId(workflowId);

        assertThat(config.isConfigured()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void nullOrBlankKeyDoesNotConfigureBrowserActDespiteAWorkflow(String apiKey) {
        config.setApiKey(apiKey);
        config.setWorkflowId("shared-product-workflow");

        assertThat(config.isConfigured()).isFalse();
    }

    @Test void missingKeyDoesNotConfigureBrowserActDespiteAWorkflow() {
        config.setWorkflowId("shared-product-workflow");

        assertThat(config.isConfigured()).isFalse();
    }
}
