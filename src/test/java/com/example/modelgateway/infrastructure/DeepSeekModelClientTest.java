package com.example.modelgateway.infrastructure;

import com.example.modelgateway.config.DeepSeekProperties;
import com.example.modelgateway.domain.ModelRequest;
import com.example.modelgateway.domain.PermanentModelException;
import com.example.modelgateway.domain.Simulation;
import com.example.modelgateway.domain.TemporaryModelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekModelClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsAuthenticatedChatRequestAndReturnsAssistantContent() throws Exception {
        var authorization = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "id":"chat-1",
                      "choices":[{
                        "index":0,
                        "message":{"role":"assistant","content":"真实模型回答"},
                        "finish_reason":"stop"
                      }],
                      "usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}
                    }
                    """);
        });
        var client = client();

        String answer = client.call(
                new ModelRequest("解释信号量", Simulation.SUCCESS),
                Duration.ofSeconds(2)
        );

        assertThat(answer).isEqualTo("真实模型回答");
        assertThat(authorization).hasValue("Bearer test-deepseek-key");
        JsonNode json = objectMapper.readTree(requestBody.get());
        assertThat(json.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(json.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(json.path("stream").asBoolean()).isFalse();
        assertThat(json.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(json.path("messages").get(1).path("content").asText()).isEqualTo("解释信号量");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void mapsRetryableHttpStatusesToTemporaryFailure(int status) {
        server.createContext("/chat/completions", exchange ->
                respond(exchange, status, "{\"error\":{\"message\":\"temporary\"}}"));

        assertThatThrownBy(() -> client().call(request(), Duration.ofSeconds(2)))
                .isInstanceOf(TemporaryModelException.class)
                .hasMessageContaining(Integer.toString(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 422})
    void mapsNonRetryableHttpStatusesToPermanentFailure(int status) {
        server.createContext("/chat/completions", exchange ->
                respond(exchange, status, "{\"error\":{\"message\":\"permanent\"}}"));

        assertThatThrownBy(() -> client().call(request(), Duration.ofSeconds(2)))
                .isInstanceOf(PermanentModelException.class)
                .hasMessageContaining(Integer.toString(status));
    }

    @Test
    void mapsHttpTimeoutToTemporaryFailure() {
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(200);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client timed out and closed the local test connection.
            }
        });

        assertThatThrownBy(() -> client().call(request(), Duration.ofMillis(30)))
                .isInstanceOf(TemporaryModelException.class)
                .hasMessageContaining("timed out");
    }

    private DeepSeekModelClient client() {
        var properties = new DeepSeekProperties(
                baseUrl,
                "test-deepseek-key",
                "deepseek-flash",
                256,
                "You are a helpful assistant."
        );
        return new DeepSeekModelClient(HttpClient.newHttpClient(), objectMapper, properties);
    }

    private static ModelRequest request() {
        return new ModelRequest("hello", Simulation.SUCCESS);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
