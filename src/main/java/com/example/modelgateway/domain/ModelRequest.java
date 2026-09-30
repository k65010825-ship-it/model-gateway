package com.example.modelgateway.domain;

import java.util.UUID;

public record ModelRequest(UUID requestId, String message, Simulation simulation) {
    public ModelRequest(String message, Simulation simulation) {
        this(UUID.randomUUID(), message, simulation);
    }
}
