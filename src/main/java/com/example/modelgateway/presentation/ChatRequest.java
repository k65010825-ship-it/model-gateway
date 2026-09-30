package com.example.modelgateway.presentation;

import com.example.modelgateway.domain.Simulation;
import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "message must not be blank") String message,
        Simulation simulation
) {
    public ChatRequest {
        if (simulation == null) {
            simulation = Simulation.SUCCESS;
        }
    }
}
