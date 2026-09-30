package com.example.modelgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.model-gateway")
public record ModelGatewayProperties(
        int maxConcurrency,
        Duration acquireTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration jitter,
        CircuitBreakerProperties circuitBreaker
) {
    public ModelGatewayProperties {
        requirePositive(maxConcurrency, "maxConcurrency");
        requirePositive(maxAttempts, "maxAttempts");
        requirePositive(acquireTimeout, "acquireTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requireNonNegative(initialBackoff, "initialBackoff");
        requireNonNegative(jitter, "jitter");
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("circuitBreaker must be configured");
        }
    }

    public static ModelGatewayProperties forTesting(
            int maxConcurrency,
            Duration acquireTimeout,
            Duration requestTimeout,
            int maxAttempts
    ) {
        return new ModelGatewayProperties(
                maxConcurrency,
                acquireTimeout,
                requestTimeout,
                maxAttempts,
                Duration.ZERO,
                Duration.ZERO,
                new CircuitBreakerProperties(5, Duration.ofSeconds(10), 2)
        );
    }

    public record CircuitBreakerProperties(
            int failureThreshold,
            Duration openDuration,
            int halfOpenPermittedCalls
    ) {
        public CircuitBreakerProperties {
            requirePositive(failureThreshold, "failureThreshold");
            requirePositive(halfOpenPermittedCalls, "halfOpenPermittedCalls");
            requirePositive(openDuration, "openDuration");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
