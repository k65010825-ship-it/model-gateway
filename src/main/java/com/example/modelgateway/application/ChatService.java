package com.example.modelgateway.application;

import com.example.modelgateway.domain.GatewayResponse;
import com.example.modelgateway.domain.ModelGateway;
import com.example.modelgateway.domain.ModelRequest;
import com.example.modelgateway.presentation.ChatRequest;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ModelGateway modelGateway;

    public ChatService(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    public GatewayResponse chat(ChatRequest request) {
        return modelGateway.generate(new ModelRequest(request.message(), request.simulation()));
    }
}
