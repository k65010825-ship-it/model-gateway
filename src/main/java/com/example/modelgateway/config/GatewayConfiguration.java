package com.example.modelgateway.config;

import com.example.modelgateway.domain.ModelClient;
import com.example.modelgateway.domain.ModelGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {
    @Bean
    ModelGateway modelGateway(
            ModelClient modelClient,
            ModelGatewayProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new ModelGateway(modelClient, properties, meterRegistry);
    }
}
