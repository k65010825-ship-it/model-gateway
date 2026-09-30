package com.example.modelgateway.config;

import com.example.modelgateway.domain.ModelClient;
import com.example.modelgateway.infrastructure.DeepSeekModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
@ConditionalOnProperty(name = "app.model.provider", havingValue = "deepseek", matchIfMissing = true)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekConfiguration {
    @Bean
    HttpClient deepSeekHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    ModelClient deepSeekModelClient(
            HttpClient deepSeekHttpClient,
            ObjectMapper objectMapper,
            DeepSeekProperties properties
    ) {
        return new DeepSeekModelClient(deepSeekHttpClient, objectMapper, properties);
    }
}
