package com.example.modelgateway.domain;

public record GatewayResponse(String answer, int attempts, boolean degraded) {
    public static GatewayResponse success(String answer, int attempts) {
        return new GatewayResponse(answer, attempts, false);
    }

    public static GatewayResponse degraded(int attempts) {
        return new GatewayResponse(
                "暂时无法调用模型，已返回降级结果；请稍后重试。",
                attempts,
                true
        );
    }
}
