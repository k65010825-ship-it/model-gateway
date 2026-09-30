package com.example.modelgateway.domain;

import com.example.modelgateway.config.ModelGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelGatewayBehaviorTest {

    @Test
    void releasesPermitAfterModelFailure() {
        var calls = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            if (calls.incrementAndGet() == 1) {
                throw new PermanentModelException("bad request");
            }
            return "recovered";
        };
        var gateway = gateway(client, properties(1, 1, Duration.ZERO, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(PermanentModelException.class);

        assertThat(gateway.generate(request()).answer()).isEqualTo("recovered");
    }

    @Test
    void rejectsWhenPermitCannotBeAcquiredInTime() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ModelClient client = (request, timeout) -> {
            entered.countDown();
            release.await();
            return "ok";
        };
        var gateway = gateway(client, properties(1, 1, Duration.ZERO, Duration.ofMillis(30)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> gateway.generate(request()));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> gateway.generate(request()))
                    .isInstanceOf(ModelBusyException.class);

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).answer()).isEqualTo("ok");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retriesTemporaryFailureAndReturnsSuccessfulAttemptCount() {
        var calls = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            if (calls.incrementAndGet() < 3) {
                throw new TemporaryModelException("try again");
            }
            return "ok";
        };
        var gateway = gateway(client, properties(2, 3, Duration.ZERO, Duration.ofSeconds(1)));

        GatewayResponse response = gateway.generate(request());

        assertThat(response.answer()).isEqualTo("ok");
        assertThat(response.attempts()).isEqualTo(3);
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void doesNotRetryPermanentFailure() {
        var calls = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            calls.incrementAndGet();
            throw new PermanentModelException("invalid prompt");
        };
        var gateway = gateway(client, properties(2, 3, Duration.ZERO, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(PermanentModelException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void returnsDegradedResponseAfterTemporaryRetriesAreExhausted() {
        var calls = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            calls.incrementAndGet();
            throw new TemporaryModelException("overloaded");
        };
        var gateway = gateway(client, properties(2, 3, Duration.ZERO, Duration.ofSeconds(1)));

        GatewayResponse response = gateway.generate(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.attempts()).isEqualTo(3);
        assertThat(response.answer()).contains("暂时无法调用模型");
        assertThat(calls).hasValue(3);
    }

    @Test
    void stopsBeforeRetryWhenTotalDeadlineIsExhausted() {
        var calls = new AtomicInteger();
        ModelClient client = (request, timeout) -> {
            calls.incrementAndGet();
            throw new TemporaryModelException("slow downstream");
        };
        var gateway = gateway(
                client,
                properties(2, 3, Duration.ofMillis(100), Duration.ofMillis(20))
        );

        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(ModelGatewayTimeoutException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void restoresInterruptFlagAndStopsProcessing() {
        ModelClient client = (request, timeout) -> {
            throw new InterruptedException("cancelled");
        };
        var gateway = gateway(client, properties(2, 3, Duration.ZERO, Duration.ofSeconds(1)));

        try {
            assertThatThrownBy(() -> gateway.generate(request()))
                    .isInstanceOf(RequestCancelledException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void opensCircuitAfterConfiguredNumberOfTemporaryFailures() {
        ModelClient client = (request, timeout) -> {
            throw new TemporaryModelException("downstream unavailable");
        };
        var properties = new ModelGatewayProperties(
                2,
                Duration.ofMillis(20),
                Duration.ofSeconds(1),
                1,
                Duration.ZERO,
                Duration.ZERO,
                new ModelGatewayProperties.CircuitBreakerProperties(
                        2,
                        Duration.ofSeconds(10),
                        1
                )
        );
        var gateway = gateway(client, properties);

        assertThat(gateway.generate(request()).degraded()).isTrue();
        assertThat(gateway.generate(request()).degraded()).isTrue();
        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(CircuitOpenException.class);
    }

    private static ModelGateway gateway(ModelClient client, ModelGatewayProperties properties) {
        return new ModelGateway(client, properties, new SimpleMeterRegistry());
    }

    private static ModelGatewayProperties properties(
            int maxConcurrency,
            int maxAttempts,
            Duration backoff,
            Duration acquireTimeout
    ) {
        return new ModelGatewayProperties(
                maxConcurrency,
                acquireTimeout,
                Duration.ofMillis(50),
                maxAttempts,
                backoff,
                Duration.ZERO,
                new ModelGatewayProperties.CircuitBreakerProperties(
                        100,
                        Duration.ofSeconds(1),
                        1
                )
        );
    }

    private static ModelRequest request() {
        return new ModelRequest("hello", Simulation.SUCCESS);
    }
}
