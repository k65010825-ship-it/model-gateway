package com.example.modelgateway.domain;

import java.time.Duration;

@FunctionalInterface
public interface ModelClient {
    String call(ModelRequest request, Duration timeout) throws ModelClientException, InterruptedException;
}
