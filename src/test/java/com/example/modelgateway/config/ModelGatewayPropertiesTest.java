package com.example.modelgateway.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelGatewayPropertiesTest {

    @Test
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> properties(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrency");
    }

    @Test
    void rejectsNonPositiveTotalDeadline() {
        assertThatThrownBy(() -> properties(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout");
    }

    private static ModelGatewayProperties properties(int concurrency, Duration timeout) {
        return new ModelGatewayProperties(
                concurrency,
                Duration.ofMillis(10),
                timeout,
                3,
                Duration.ZERO,
                Duration.ZERO,
                new ModelGatewayProperties.CircuitBreakerProperties(
                        2,
                        Duration.ofSeconds(1),
                        1
                )
        );
    }
}
