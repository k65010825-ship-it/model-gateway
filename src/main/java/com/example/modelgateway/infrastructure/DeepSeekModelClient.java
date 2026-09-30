package com.example.modelgateway.infrastructure;

import com.example.modelgateway.config.DeepSeekProperties;
import com.example.modelgateway.domain.ModelClient;
import com.example.modelgateway.domain.ModelRequest;
import com.example.modelgateway.domain.PermanentModelException;
import com.example.modelgateway.domain.TemporaryModelException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

public final class DeepSeekModelClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DeepSeekProperties properties;
    private final URI chatCompletionsUri;

    public DeepSeekModelClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            DeepSeekProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatCompletionsUri = URI.create(
                stripTrailingSlash(properties.baseUrl().toString()) + "/chat/completions"
        );
    }

    @Override
    public String call(ModelRequest request, Duration timeout) throws InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(chatCompletionsUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            return parseResponse(response);
        } catch (HttpTimeoutException timeoutFailure) {
            throw new TemporaryModelException("DeepSeek request timed out");
        } catch (IOException networkFailure) {
            throw new TemporaryModelException("DeepSeek network request failed");
        }
    }

    private String requestBody(ModelRequest request) {
        var payload = new ChatCompletionRequest(
                properties.model(),
                List.of(
                        new Message("system", properties.systemPrompt()),
                        new Message("user", request.message())
                ),
                properties.maxTokens(),
                false
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new PermanentModelException("Failed to serialize DeepSeek request");
        }
    }

    private String parseResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw statusFailure(status);
        }
        try {
            String content = objectMapper.readTree(response.body())
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();
            if (content.isBlank()) {
                throw new TemporaryModelException("DeepSeek returned an empty response");
            }
            return content;
        } catch (JsonProcessingException malformedResponse) {
            throw new TemporaryModelException("DeepSeek returned malformed JSON");
        }
    }

    private static RuntimeException statusFailure(int status) {
        String message = "DeepSeek HTTP " + status;
        if (status == 429 || status >= 500) {
            return new TemporaryModelException(message);
        }
        return new PermanentModelException(message);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record ChatCompletionRequest(
            String model,
            List<Message> messages,
            int max_tokens,
            boolean stream
    ) {
    }

    private record Message(String role, String content) {
    }

}
