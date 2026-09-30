package com.example.modelgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.fake-model")
public record FakeModelProperties(Duration slowDelay) {
    public FakeModelProperties {
        if (slowDelay == null || slowDelay.isZero() || slowDelay.isNegative()) {
            throw new IllegalArgumentException("slowDelay must be positive");
        }
    }
}
