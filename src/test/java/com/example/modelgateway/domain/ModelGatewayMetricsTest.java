package com.example.modelgateway.domain;

import com.example.modelgateway.config.ModelGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayMetricsTest {

    @Test
    void recordsCallsRetriesDurationsAndActiveCallGauge() {
        var attempts = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new TemporaryModelException("retry");
            }
            return "ok";
        };
        var registry = new SimpleMeterRegistry();
        var gateway = new ModelGateway(client, properties(3), registry);

        gateway.generate(new ModelRequest("hello", Simulation.SUCCESS));

        assertThat(registry.get("model.gateway.calls")
                .tag("result", "temporary_failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("model.gateway.calls")
                .tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("model.gateway.retries").counter().count()).isEqualTo(1);
        assertThat(registry.get("model.gateway.wait.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("model.gateway.call.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("model.gateway.active.calls").gauge().value()).isZero();
        assertThat(registry.get("model.gateway.available.permits").gauge().value()).isEqualTo(2);
    }

    @Test
    void recordsFallbackWhenTemporaryRetriesAreExhausted() {
        ModelClient client = (request, timeout) -> {
            throw new TemporaryModelException("still down");
        };
        var registry = new SimpleMeterRegistry();
        var gateway = new ModelGateway(client, properties(2), registry);

        assertThat(gateway.generate(new ModelRequest("hello", Simulation.SUCCESS)).degraded()).isTrue();

        assertThat(registry.get("model.gateway.fallbacks").counter().count()).isEqualTo(1);
    }

    private static ModelGatewayProperties properties(int maxAttempts) {
        return new ModelGatewayProperties(
                2,
                Duration.ofMillis(20),
                Duration.ofSeconds(1),
                maxAttempts,
                Duration.ZERO,
                Duration.ZERO,
                new ModelGatewayProperties.CircuitBreakerProperties(
                        10,
                        Duration.ofSeconds(1),
                        1
                )
        );
    }
}
