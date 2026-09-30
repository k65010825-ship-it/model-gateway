package com.example.modelgateway.domain;

import com.example.modelgateway.config.ModelGatewayProperties;
import com.example.modelgateway.infrastructure.GatewayMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class ModelGateway {
    private final ModelClient client;
    private final ModelGatewayProperties properties;
    private final Semaphore permits;
    private final SimpleCircuitBreaker circuitBreaker;
    private final GatewayMetrics metrics;

    public ModelGateway(
            ModelClient client,
            ModelGatewayProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.client = client;
        this.properties = properties;
        this.permits = new Semaphore(properties.maxConcurrency(), true);
        var circuit = properties.circuitBreaker();
        this.circuitBreaker = new SimpleCircuitBreaker(
                circuit.failureThreshold(),
                circuit.openDuration(),
                circuit.halfOpenPermittedCalls()
        );
        this.metrics = new GatewayMetrics(meterRegistry, permits, circuitBreaker);
    }

    public GatewayResponse generate(ModelRequest request) {
        long deadlineNanos = System.nanoTime() + properties.requestTimeout().toNanos();
        try {
            for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
                long remainingNanos = remainingNanos(deadlineNanos);
                if (remainingNanos <= 0) {
                    throw deadlineExceeded();
                }

                SimpleCircuitBreaker.CallPermit circuitPermit;
                try {
                    circuitPermit = circuitBreaker.acquirePermission();
                } catch (CircuitOpenException circuitOpen) {
                    metrics.circuitRejected();
                    throw circuitOpen;
                }
                boolean acquired = false;
                boolean circuitCompleted = false;
                boolean callStarted = false;
                long callStartedNanos = 0;
                try {
                    long acquireNanos = Math.min(
                            properties.acquireTimeout().toNanos(),
                            remainingNanos
                    );
                    long waitStartedNanos = metrics.startTimer();
                    try {
                        acquired = permits.tryAcquire(acquireNanos, TimeUnit.NANOSECONDS);
                    } finally {
                        metrics.recordWait(waitStartedNanos);
                    }
                    if (!acquired) {
                        if (remainingNanos(deadlineNanos) <= 0) {
                            throw deadlineExceeded();
                        }
                        metrics.concurrencyRejected();
                        throw new ModelBusyException("Model concurrency limit reached");
                    }

                    Duration callTimeout = Duration.ofNanos(remainingNanosOrThrow(deadlineNanos));
                    metrics.callStarted();
                    callStarted = true;
                    callStartedNanos = metrics.startTimer();
                    String answer = client.call(request, callTimeout);
                    metrics.callResult("success");
                    circuitBreaker.onSuccess(circuitPermit);
                    circuitCompleted = true;
                    return GatewayResponse.success(answer, attempt);
                } catch (TemporaryModelException temporaryFailure) {
                    metrics.callResult("temporary_failure");
                    circuitBreaker.onFailure(circuitPermit);
                    circuitCompleted = true;
                    if (attempt == properties.maxAttempts()) {
                        metrics.fallback();
                        return GatewayResponse.degraded(attempt);
                    }
                    metrics.retry();
                    backoff(attempt, deadlineNanos);
                } catch (PermanentModelException permanentFailure) {
                    metrics.callResult("permanent_failure");
                    circuitBreaker.onSuccess(circuitPermit);
                    circuitCompleted = true;
                    throw permanentFailure;
                } catch (InterruptedException interrupted) {
                    if (callStarted) {
                        metrics.callResult("cancelled");
                    }
                    circuitBreaker.onIgnored(circuitPermit);
                    circuitCompleted = true;
                    throw interrupted;
                } finally {
                    if (callStarted) {
                        metrics.callFinished(callStartedNanos);
                    }
                    if (acquired) {
                        permits.release();
                    }
                    if (!circuitCompleted) {
                        circuitBreaker.onIgnored(circuitPermit);
                    }
                }
            }
            throw new IllegalStateException("Retry loop ended unexpectedly");
        } catch (ModelGatewayTimeoutException timeout) {
            metrics.timedOut();
            throw timeout;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RequestCancelledException("Request was interrupted", interrupted);
        }
    }

    private void backoff(int failedAttempt, long deadlineNanos) throws InterruptedException {
        long baseNanos = properties.initialBackoff().toNanos();
        long multiplier = 1L << Math.min(failedAttempt - 1, 20);
        long exponentialNanos = saturatedMultiply(baseNanos, multiplier);
        long jitterBound = properties.jitter().toNanos();
        long jitterNanos = jitterBound == 0
                ? 0
                : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        long sleepNanos = saturatedAdd(exponentialNanos, jitterNanos);
        long remainingNanos = remainingNanosOrThrow(deadlineNanos);
        if (sleepNanos >= remainingNanos) {
            throw deadlineExceeded();
        }
        if (sleepNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static long remainingNanosOrThrow(long deadlineNanos) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            throw deadlineExceeded();
        }
        return remaining;
    }

    private static ModelGatewayTimeoutException deadlineExceeded() {
        return new ModelGatewayTimeoutException("Total request deadline exceeded");
    }

    private static long saturatedMultiply(long value, long multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
