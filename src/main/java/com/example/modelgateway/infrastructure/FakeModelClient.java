package com.example.modelgateway.infrastructure;

import com.example.modelgateway.config.FakeModelProperties;
import com.example.modelgateway.domain.ModelClient;
import com.example.modelgateway.domain.ModelRequest;
import com.example.modelgateway.domain.PermanentModelException;
import com.example.modelgateway.domain.TemporaryModelException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "fake")
public final class FakeModelClient implements ModelClient {
    private final FakeModelProperties properties;
    private final ConcurrentHashMap<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public FakeModelClient(FakeModelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String call(ModelRequest request, Duration timeout) throws InterruptedException {
        return switch (request.simulation()) {
            case SUCCESS -> answer(request);
            case SLOW -> slowAnswer(request, timeout);
            case TEMPORARY_FAILURE -> temporaryFailureThenSuccess(request);
            case PERMANENT_FAILURE -> throw new PermanentModelException(
                    "The simulated model rejected this request"
            );
            case INTERRUPTED -> throw new InterruptedException("Simulated client cancellation");
        };
    }

    private String slowAnswer(ModelRequest request, Duration timeout) throws InterruptedException {
        Duration delay = properties.slowDelay();
        if (delay.compareTo(timeout) > 0) {
            TimeUnit.NANOSECONDS.sleep(timeout.toNanos());
            throw new TemporaryModelException("Simulated model call timed out");
        }
        TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        return answer(request);
    }

    private String temporaryFailureThenSuccess(ModelRequest request) {
        int currentAttempt = attempts
                .computeIfAbsent(request.requestId(), ignored -> new AtomicInteger())
                .incrementAndGet();
        if (currentAttempt <= 2) {
            throw new TemporaryModelException("Simulated temporary model failure");
        }
        attempts.remove(request.requestId());
        return answer(request);
    }

    private static String answer(ModelRequest request) {
        return "模拟模型回答：" + request.message();
    }
}
