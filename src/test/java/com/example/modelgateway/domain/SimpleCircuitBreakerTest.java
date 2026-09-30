package com.example.modelgateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleCircuitBreakerTest {

    @Test
    void opensAfterFailureThresholdAndRejectsNewCalls() {
        var clock = new AtomicLong();
        var breaker = new SimpleCircuitBreaker(2, Duration.ofSeconds(10), 1, clock::get);

        failOneCall(breaker);
        failOneCall(breaker);

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThatThrownBy(breaker::acquirePermission)
                .isInstanceOf(CircuitOpenException.class);
    }

    @Test
    void halfOpenAllowsOnlyConfiguredProbeCount() {
        var clock = new AtomicLong();
        var breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(10), 1, clock::get);
        failOneCall(breaker);
        clock.addAndGet(Duration.ofSeconds(10).toNanos());

        SimpleCircuitBreaker.CallPermit probe = breaker.acquirePermission();

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThatThrownBy(breaker::acquirePermission)
                .isInstanceOf(CircuitOpenException.class);

        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void failedProbeReopensBreakerForAnotherOpenDuration() {
        var clock = new AtomicLong();
        var breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(10), 1, clock::get);
        failOneCall(breaker);
        clock.addAndGet(Duration.ofSeconds(10).toNanos());

        SimpleCircuitBreaker.CallPermit probe = breaker.acquirePermission();
        breaker.onFailure(probe);

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThatThrownBy(breaker::acquirePermission)
                .isInstanceOf(CircuitOpenException.class);
    }

    private static void failOneCall(SimpleCircuitBreaker breaker) {
        SimpleCircuitBreaker.CallPermit permit = breaker.acquirePermission();
        breaker.onFailure(permit);
    }
}
