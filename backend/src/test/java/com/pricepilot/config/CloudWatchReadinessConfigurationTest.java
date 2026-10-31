package com.pricepilot.config;

import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.metrics.CloudWatchExportAutoConfiguration;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchReadinessConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // These tests must not use the host's credentials, profiles or environment.
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/")
                .withUserConfiguration(IsolatedAutoConfiguration.class, CloudWatchReadinessConfiguration.class);
    }

    // This import path honors spring.autoconfigure.exclude like a real Boot startup.
    // Only metrics/AWS configuration is imported: no application services, database or SMTP.
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({AwsAutoConfiguration.class, CredentialsProviderAutoConfiguration.class,
            RegionProviderAutoConfiguration.class, CloudWatchExportAutoConfiguration.class,
            MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class})
    static class IsolatedAutoConfiguration { }

    @Test void defaultStartupCreatesNoAwsProvidersClientsOrExporter() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AwsCredentialsProvider.class)
                    .doesNotHaveBean(AwsRegionProvider.class)
                    .doesNotHaveBean(CloudWatchAsyncClient.class)
                    .doesNotHaveBean(CloudWatchMeterRegistry.class);
            assertThat(context.getEnvironment().getProperty("management.cloudwatch.metrics.export.enabled"))
                    .isEqualTo("false");
        });
    }

    @Test void devProfileDoesNotActivateCloudWatchEvenWhenNamespaceAndRegionArePresent() {
        runner().withPropertyValues("spring.profiles.active=dev", "CLOUDWATCH_NAMESPACE=Fixture/Local",
                "AWS_REGION=ap-south-1", "management.cloudwatch.metrics.export.namespace=Fixture/Unexpected")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AwsCredentialsProvider.class)
                            .doesNotHaveBean(AwsRegionProvider.class)
                            .doesNotHaveBean(CloudWatchAsyncClient.class)
                            .doesNotHaveBean(CloudWatchMeterRegistry.class);
                });
    }

    @Test void cloudWatchProfileBindsExplicitSettingsAndUsesOnlyAFakeClientInThisTest() {
        CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
        when(client.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));
        runner().withPropertyValues("spring.profiles.active=cloudwatch", "CLOUDWATCH_NAMESPACE=Fixture/PricePilot",
                "AWS_REGION=ap-south-1", "CLOUDWATCH_STEP=2m")
                .withBean(CloudWatchAsyncClient.class, () -> client)
                .withBean(AwsCredentialsProvider.class, AnonymousCredentialsProvider::create)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CloudWatchMeterRegistry.class);
                    assertThat(context.getEnvironment().getProperty("management.cloudwatch.metrics.export.namespace"))
                            .isEqualTo("Fixture/PricePilot");
                    assertThat(context.getEnvironment().getProperty("management.cloudwatch.metrics.export.step"))
                            .isEqualTo("2m");
                    assertThat(context.getEnvironment().getProperty("spring.cloud.aws.credentials.instance-profile"))
                            .isEqualTo("true");
                    assertThat(context.getEnvironment().getProperty("spring.cloud.aws.credentials.access-key")).isNull();
                    assertThat(context.getEnvironment().getProperty("spring.cloud.aws.credentials.secret-key")).isNull();
                    assertThat(context.getBean(AwsRegionProvider.class).getRegion().id()).isEqualTo("ap-south-1");
                    assertThat(context.getBean(CloudWatchAsyncClient.class)).isSameAs(client);
                });
    }

    @Test void selectedProfileFailsWithoutNamespaceBeforeAnyAwsClientIsCreated() {
        AtomicInteger clientCreations = new AtomicInteger();
        runner().withPropertyValues("spring.profiles.active=cloudwatch", "AWS_REGION=ap-south-1")
                .withBean(CloudWatchAsyncClient.class, () -> {
                    clientCreations.incrementAndGet();
                    return mock(CloudWatchAsyncClient.class);
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("CLOUDWATCH_NAMESPACE");
                    assertThat(clientCreations).hasValue(0);
                });
    }

    @Test void selectedProfileFailsWithoutRegionBeforeAnyAwsClientIsCreated() {
        AtomicInteger clientCreations = new AtomicInteger();
        runner().withPropertyValues("spring.profiles.active=cloudwatch", "CLOUDWATCH_NAMESPACE=Fixture/PricePilot")
                .withBean(CloudWatchAsyncClient.class, () -> {
                    clientCreations.incrementAndGet();
                    return mock(CloudWatchAsyncClient.class);
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("AWS_REGION");
                    assertThat(clientCreations).hasValue(0);
                });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " AWS/EC2", "AWS/EC2", ":bad", "bad\nnamespace"})
    void rejectsMissingOrInvalidNamespace(String namespace) {
        MockEnvironment environment = new MockEnvironment().withProperty("AWS_REGION", "ap-south-1");
        if (namespace != null) environment.setProperty("CLOUDWATCH_NAMESPACE", namespace);
        assertThatThrownBy(() -> CloudWatchReadinessConfiguration.validateEnvironment(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLOUDWATCH_NAMESPACE");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ap-south", " ap-south-1", "AP-SOUTH-1", "https://example.test", "${AWS_REGION}"})
    void rejectsMissingOrInvalidRegion(String region) {
        MockEnvironment environment = new MockEnvironment().withProperty("CLOUDWATCH_NAMESPACE", "Fixture/PricePilot");
        if (region != null) environment.setProperty("AWS_REGION", region);
        assertThatThrownBy(() -> CloudWatchReadinessConfiguration.validateEnvironment(environment))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class)
                .hasMessageContaining("AWS_REGION");
    }

    @Test void acceptsRegionShapesWithoutHardcodingAnAwsAccountOrRegion() {
        for (String region : new String[]{"ap-south-1", "us-gov-west-1", "us-iso-east-1"}) {
            CloudWatchReadinessConfiguration.validateEnvironment(new MockEnvironment()
                    .withProperty("CLOUDWATCH_NAMESPACE", "Fixture/PricePilot").withProperty("AWS_REGION", region));
        }
    }

    @Test void namespaceLengthLimitIsCheckedBeforeClientCreation() {
        MockEnvironment environment = new MockEnvironment().withProperty("AWS_REGION", "ap-south-1")
                .withProperty("CLOUDWATCH_NAMESPACE", "x".repeat(255));
        CloudWatchReadinessConfiguration.validateEnvironment(environment);
        environment.setProperty("CLOUDWATCH_NAMESPACE", "x".repeat(256));
        assertThatThrownBy(() -> CloudWatchReadinessConfiguration.validateEnvironment(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLOUDWATCH_NAMESPACE");
    }

    @Test void shutdownAndManualJobDefaultsAllowAnInflightBrowserActCheckToDrain() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            var phase = DurationStyle.detectAndParse(environment.getRequiredProperty("spring.lifecycle.timeout-per-shutdown-phase"));
            var manual = Duration.ofSeconds(environment.getRequiredProperty("pricepilot.manual-refresh.shutdown-grace-seconds", Long.class));
            var scheduled = Duration.ofSeconds(environment.getRequiredProperty("pricepilot.scheduler.shutdown-grace-seconds", Long.class));
            var browserAct = Duration.ofMillis(environment.getRequiredProperty("pricepilot.browser-act.timeout-ms", Long.class));
            assertThat(phase).isEqualTo(Duration.ofSeconds(420)).isGreaterThan(manual).isGreaterThan(scheduled);
            assertThat(manual).isEqualTo(Duration.ofSeconds(400)).isGreaterThan(browserAct);
            assertThat(scheduled).isEqualTo(manual);
            assertThat(environment.getProperty("pricepilot.manual-refresh.workers", Integer.class)).isEqualTo(1);
            assertThat(environment.getProperty("pricepilot.manual-refresh.queue-capacity", Integer.class)).isEqualTo(8);
            assertThat(environment.getProperty("pricepilot.manual-refresh.max-jobs", Integer.class)).isEqualTo(200);
            assertThat(environment.getProperty("pricepilot.manual-refresh.retention-seconds", Integer.class)).isEqualTo(3600);
        });
    }

    @Test void shutdownAndManualJobSettingsCanBeOverriddenThroughEnvironmentNames() {
        runner().withPropertyValues("SHUTDOWN_TIMEOUT=8m", "MANUAL_REFRESH_WORKERS=2",
                "MANUAL_REFRESH_QUEUE_CAPACITY=4", "MANUAL_REFRESH_MAX_JOBS=80",
                "MANUAL_REFRESH_RETENTION_SECONDS=1800", "MANUAL_REFRESH_SHUTDOWN_GRACE_SECONDS=350",
                "SCHEDULER_SHUTDOWN_GRACE_SECONDS=350").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("8m");
            assertThat(environment.getProperty("pricepilot.manual-refresh.workers", Integer.class)).isEqualTo(2);
            assertThat(environment.getProperty("pricepilot.manual-refresh.queue-capacity", Integer.class)).isEqualTo(4);
            assertThat(environment.getProperty("pricepilot.manual-refresh.max-jobs", Integer.class)).isEqualTo(80);
            assertThat(environment.getProperty("pricepilot.manual-refresh.retention-seconds", Integer.class)).isEqualTo(1800);
            assertThat(environment.getProperty("pricepilot.manual-refresh.shutdown-grace-seconds", Integer.class)).isEqualTo(350);
            assertThat(environment.getProperty("pricepilot.scheduler.shutdown-grace-seconds", Integer.class)).isEqualTo(350);
        });
    }
}
