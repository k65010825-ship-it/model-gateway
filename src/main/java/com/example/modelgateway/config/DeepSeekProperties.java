package com.example.modelgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("app.model.deepseek")
public record DeepSeekProperties(
        URI baseUrl,
        String apiKey,
        String model,
        int maxTokens,
        String systemPrompt
) {
    public DeepSeekProperties {
        if (baseUrl == null || !baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("baseUrl must be an absolute URI");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DEEPSEEK_API_KEY is required when provider=deepseek");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
    }
}
