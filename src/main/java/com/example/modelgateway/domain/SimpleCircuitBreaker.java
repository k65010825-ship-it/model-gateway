package com.example.modelgateway.domain;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class SimpleCircuitBreaker {
    private final int failureThreshold;
    private final long openDurationNanos;
    private final int halfOpenPermittedCalls;
    private final LongSupplier nanoClock;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private long openUntilNanos;
    private int halfOpenInFlight;
    private int halfOpenSuccesses;

    public SimpleCircuitBreaker(
            int failureThreshold,
            Duration openDuration,
            int halfOpenPermittedCalls
    ) {
        this(failureThreshold, openDuration, halfOpenPermittedCalls, System::nanoTime);
    }

    SimpleCircuitBreaker(
            int failureThreshold,
            Duration openDuration,
            int halfOpenPermittedCalls,
            LongSupplier nanoClock
    ) {
        if (failureThreshold < 1 || halfOpenPermittedCalls < 1 || openDuration.isNegative()) {
            throw new IllegalArgumentException("Circuit breaker values must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationNanos = openDuration.toNanos();
        this.halfOpenPermittedCalls = halfOpenPermittedCalls;
        this.nanoClock = nanoClock;
    }

    public synchronized CallPermit acquirePermission() {
        transitionToHalfOpenIfReady();
        if (state == CircuitState.OPEN) {
            throw new CircuitOpenException("Model circuit breaker is open");
        }
        if (state == CircuitState.HALF_OPEN) {
            if (halfOpenInFlight >= halfOpenPermittedCalls) {
                throw new CircuitOpenException("Model circuit breaker probe limit reached");
            }
            halfOpenInFlight++;
            return new CallPermit(CircuitState.HALF_OPEN);
        }
        return new CallPermit(CircuitState.CLOSED);
    }

    public synchronized void onSuccess(CallPermit permit) {
        if (permit.stateAtAcquisition() == CircuitState.CLOSED) {
            if (state == CircuitState.CLOSED) {
                consecutiveFailures = 0;
            }
            return;
        }
        if (state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= halfOpenPermittedCalls) {
                close();
            }
        }
    }

    public synchronized void onFailure(CallPermit permit) {
        if (permit.stateAtAcquisition() == CircuitState.HALF_OPEN) {
            if (state == CircuitState.HALF_OPEN) {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
                open();
            }
            return;
        }
        if (state == CircuitState.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                open();
            }
        }
    }

    public synchronized void onIgnored(CallPermit permit) {
        if (permit.stateAtAcquisition() == CircuitState.HALF_OPEN
                && state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
        }
    }

    public synchronized CircuitState state() {
        transitionToHalfOpenIfReady();
        return state;
    }

    private void transitionToHalfOpenIfReady() {
        if (state == CircuitState.OPEN && nanoClock.getAsLong() >= openUntilNanos) {
            state = CircuitState.HALF_OPEN;
            halfOpenInFlight = 0;
            halfOpenSuccesses = 0;
        }
    }

    private void open() {
        state = CircuitState.OPEN;
        openUntilNanos = nanoClock.getAsLong() + openDurationNanos;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
    }

    private void close() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
    }

    public record CallPermit(CircuitState stateAtAcquisition) {
    }
}
