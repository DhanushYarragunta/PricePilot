package com.pricepilot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserActBindingTest {
    private AppProperties.BrowserAct bind(MockEnvironment environment) throws Exception {
        var base = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0);
        environment.getPropertySources().addLast(base);
        return Binder.get(environment).bind("pricepilot.browser-act", Bindable.of(AppProperties.BrowserAct.class)).get();
    }

    @Test void sharedWorkflowAndRuntimeSettingsBindFromEnvironment() throws Exception {
        var settings = bind(new MockEnvironment()
                .withProperty("BROWSERACT_API_KEY", "test-key")
                .withProperty("BROWSERACT_WORKFLOW_ID", "shared-product-workflow")
                .withProperty("BROWSERACT_TIMEOUT_MS", "45000")
                .withProperty("BROWSERACT_MONTHLY_CALL_BUDGET", "-1"));

        assertThat(settings.getApiKey()).isEqualTo("test-key");
        assertThat(settings.getWorkflowId()).isEqualTo("shared-product-workflow");
        assertThat(settings.getTimeoutMs()).isEqualTo(45000);
        assertThat(settings.getMonthlyCallBudget()).isEqualTo(-1);
        assertThat(settings.isConfigured()).isTrue();
    }

    @Test void missingWorkflowEnvironmentDoesNotConfigureBrowserActDespiteAKey() throws Exception {
        var settings = bind(new MockEnvironment().withProperty("BROWSERACT_API_KEY", "test-key"));

        assertThat(settings.getWorkflowId()).isEmpty();
        assertThat(settings.isConfigured()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void nullOrBlankWorkflowEnvironmentDoesNotConfigureBrowserActDespiteAKey(String workflowId) throws Exception {
        var environment = new MockEnvironment().withProperty("BROWSERACT_API_KEY", "test-key");
        // Environment variables are strings; a null test value represents an unset variable.
        if (workflowId != null) environment.setProperty("BROWSERACT_WORKFLOW_ID", workflowId);
        var settings = bind(environment);

        assertThat(settings.isConfigured()).isFalse();
    }

    @Test void removedWorkflowEnvironmentVariablesCannotReplaceTheSharedWorkflowId() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("BROWSERACT_API_KEY", "test-key")
                .withProperty("BROWSERACT_DEFAULT_WORKFLOW_ID", "obsolete-default")
                .withProperty("BROWSERACT_AMAZON_WORKFLOW_ID", "obsolete-amazon")
                .withProperty("BROWSERACT_FLIPKART_WORKFLOW_ID", "obsolete-flipkart");

        assertThat(bind(environment).isConfigured()).isFalse();

        environment.setProperty("BROWSERACT_WORKFLOW_ID", "shared-product-workflow");
        var settings = bind(environment);
        assertThat(settings.getWorkflowId()).isEqualTo("shared-product-workflow");
        assertThat(settings.isConfigured()).isTrue();
    }

    @Test void applicationYamlExposesOnlyOneWorkflowSetting() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);

        assertThat(source).isInstanceOf(EnumerablePropertySource.class);
        assertThat(((EnumerablePropertySource<?>) source).getPropertyNames())
                .filteredOn(name -> name.startsWith("pricepilot.browser-act.workflow")
                        || name.startsWith("pricepilot.browser-act.default-workflow"))
                .containsExactly("pricepilot.browser-act.workflow-id");
    }
}
