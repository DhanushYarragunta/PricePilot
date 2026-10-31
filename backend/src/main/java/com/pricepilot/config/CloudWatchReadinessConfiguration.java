package com.pricepilot.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Reject incomplete opt-in settings before AWS clients or publishing threads start. */
@Configuration(proxyBeanMethods = false)
@Profile("cloudwatch")
public class CloudWatchReadinessConfiguration {
    @Bean
    static BeanFactoryPostProcessor cloudWatchEnvironmentGuard(Environment environment) {
        return beanFactory -> validateEnvironment(environment);
    }

    static void validateEnvironment(Environment environment) {
        String namespace = environment.getProperty("CLOUDWATCH_NAMESPACE");
        if (namespace == null || namespace.isBlank() || namespace.length() > 255
                || !namespace.equals(namespace.trim()) || namespace.startsWith("AWS/")
                || namespace.charAt(0) == ':'
                || namespace.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalStateException("CLOUDWATCH_NAMESPACE must be a nonblank custom namespace "
                    + "of at most 255 printable ASCII characters, without edge whitespace or an AWS/ prefix.");
        }

        String region = environment.getProperty("AWS_REGION");
        if (region == null || !region.matches("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+")) {
            throw new IllegalStateException("AWS_REGION must be explicitly configured as an AWS region identifier.");
        }
    }
}
