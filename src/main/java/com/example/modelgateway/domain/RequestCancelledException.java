package com.example.modelgateway.domain;

public class RequestCancelledException extends RuntimeException {
    public RequestCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
