package com.example.modelgateway.domain;

public final class ModelBusyException extends RuntimeException {
    public ModelBusyException(String message) {
        super(message);
    }
}
