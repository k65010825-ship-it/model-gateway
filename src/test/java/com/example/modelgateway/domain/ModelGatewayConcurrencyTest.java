package com.example.modelgateway.domain;

import com.example.modelgateway.config.ModelGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayConcurrencyTest {

    @Test
    void neverCallsModelAboveConfiguredConcurrencyLimit() throws Exception {
        var active = new AtomicInteger();
        var maximumObserved = new AtomicInteger();
        var releaseCalls = new CountDownLatch(1);

        ModelClient client = (request, timeout) -> {
            int current = active.incrementAndGet();
            maximumObserved.accumulateAndGet(current, Math::max);
            try {
                releaseCalls.await();
                return "answer:" + request.message();
            } finally {
                active.decrementAndGet();
            }
        };

        var properties = ModelGatewayProperties.forTesting(
                2,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                1
        );
        var gateway = new ModelGateway(client, properties, new SimpleMeterRegistry());

        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<GatewayResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> gateway.generate(
                        new ModelRequest("hello", Simulation.SUCCESS))));
            }

            Thread.sleep(150);
            assertThat(maximumObserved.get()).isEqualTo(2);

            releaseCalls.countDown();
            for (Future<GatewayResponse> future : futures) {
                assertThat(future.get().answer()).isEqualTo("answer:hello");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
