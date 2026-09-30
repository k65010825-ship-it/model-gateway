package com.example.modelgateway;

import com.example.modelgateway.config.FakeModelProperties;
import com.example.modelgateway.config.ModelGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ModelGatewayProperties.class, FakeModelProperties.class})
public class ModelGatewayDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelGatewayDemoApplication.class, args);
    }
}
