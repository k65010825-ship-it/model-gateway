package com.example.modelgateway.infrastructure;

import com.example.modelgateway.domain.SimpleCircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class GatewayMetrics {
    private final Map<String, Counter> calls;
    private final Counter retries;
    private final Counter concurrencyRejections;
    private final Counter circuitRejections;
    private final Counter timeouts;
    private final Counter fallbacks;
    private final Timer waitDuration;
    private final Timer callDuration;
    private final AtomicInteger activeCalls = new AtomicInteger();

    public GatewayMetrics(
            MeterRegistry registry,
            Semaphore permits,
            SimpleCircuitBreaker circuitBreaker
    ) {
        calls = Map.of(
                "success", callCounter(registry, "success"),
                "temporary_failure", callCounter(registry, "temporary_failure"),
                "permanent_failure", callCounter(registry, "permanent_failure"),
                "cancelled", callCounter(registry, "cancelled")
        );
        retries = registry.counter("model.gateway.retries");
        concurrencyRejections = Counter.builder("model.gateway.rejections")
                .tag("type", "concurrency")
                .register(registry);
        circuitRejections = Counter.builder("model.gateway.rejections")
                .tag("type", "circuit")
                .register(registry);
        timeouts = registry.counter("model.gateway.timeouts");
        fallbacks = registry.counter("model.gateway.fallbacks");
        waitDuration = registry.timer("model.gateway.wait.duration");
        callDuration = registry.timer("model.gateway.call.duration");

        registry.gauge("model.gateway.active.calls", activeCalls);
        registry.gauge("model.gateway.available.permits", permits, Semaphore::availablePermits);
        registry.gauge(
                "model.gateway.circuit.state",
                circuitBreaker,
                breaker -> breaker.state().ordinal()
        );
    }

    public long startTimer() {
        return System.nanoTime();
    }

    public void recordWait(long startedNanos) {
        waitDuration.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void callStarted() {
        activeCalls.incrementAndGet();
    }

    public void callFinished(long startedNanos) {
        activeCalls.decrementAndGet();
        callDuration.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void callResult(String result) {
        calls.get(result).increment();
    }

    public void retry() {
        retries.increment();
    }

    public void concurrencyRejected() {
        concurrencyRejections.increment();
    }

    public void circuitRejected() {
        circuitRejections.increment();
    }

    public void timedOut() {
        timeouts.increment();
    }

    public void fallback() {
        fallbacks.increment();
    }

    private static Counter callCounter(MeterRegistry registry, String result) {
        return Counter.builder("model.gateway.calls")
                .tag("result", result)
                .register(registry);
    }
}
