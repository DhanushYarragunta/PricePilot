package com.pricepilot.ai;

import com.pricepilot.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationTest {
    @Test void compatibleProviderDoesNotInheritAnthropicCredentialsHostOrModel() {
        AppProperties.Ai ai = new AppProperties.Ai();
        ai.setAnthropicApiKey("legacy-test-key");
        assertThat(ai.isConfigured()).isTrue();
        ai.setProvider("openai-compatible");
        assertThat(ai.isConfigured()).isFalse();
        assertThat(ai.getApiKey()).isEmpty();
        assertThat(ai.getBaseUrl()).isEmpty();
        assertThat(ai.getModel()).isEmpty();
        assertThat(ai.getMaxTokens()).isEqualTo(4096);
    }

    @Test void emptyPricingSettingsBindAsUnknownAndExactModelAliasIsPreserved() {
        var source = new MapConfigurationPropertySource(Map.of(
                "pricepilot.ai.provider", "openai-compatible",
                "pricepilot.ai.api-key", "test-only-key",
                "pricepilot.ai.base-url", "https://gateway.example/v1",
                "pricepilot.ai.model", "gpt-6-astra",
                "pricepilot.ai.input-usd-per-million", "",
                "pricepilot.ai.output-usd-per-million", "",
                "pricepilot.ai.cached-input-usd-per-million", ""));
        var ai = new Binder(source).bind("pricepilot", Bindable.of(AppProperties.class)).get().getAi();
        assertThat(ai.isConfigured()).isTrue();
        assertThat(ai.getModel()).isEqualTo("gpt-6-astra");
        assertThat(ai.hasConfiguredPricing()).isFalse();
        assertThat(ai.getInputUsdPerMillion()).isNull();
    }

    @Test void rejectsUnencryptedOrCredentialBearingProviderUrls() {
        AppProperties.Ai ai = new AppProperties.Ai();
        ai.setApiKey("test-only-key");
        for (String url : java.util.List.of("http://gateway.example", "https://key@gateway.example", "https://gateway.example?key=secret")) {
            ai.setBaseUrl(url);
            assertThat(ai.isConfigured()).isFalse();
        }
    }
}
