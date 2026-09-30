package com.example.modelgateway.presentation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.model.provider=fake",
        "app.model-gateway.request-timeout=80ms",
        "app.model-gateway.initial-backoff=1ms",
        "app.fake-model.slow-delay=200ms"
})
@AutoConfigureMockMvc
class ChatApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successfulRequestReturnsModelAnswer() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"什么是信号量？","simulation":"SUCCESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("模拟模型回答：什么是信号量？"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void blankMessageReturnsBadRequestProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":" ","simulation":"SUCCESS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数不合法"));
    }

    @Test
    void omittedSimulationDefaultsToSuccessForRealModelCompatibleRequests() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("模拟模型回答：hello"))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void temporaryFailureIsRetriedAndEventuallySucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"retry","simulation":"TEMPORARY_FAILURE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void permanentModelFailureReturnsBadGateway() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"invalid","simulation":"PERMANENT_FAILURE"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("模型拒绝请求"));
    }

    @Test
    void slowModelCannotExceedTotalRequestDeadline() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"slow","simulation":"SLOW"}
                                """))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.title").value("请求超过总截止时间"));
    }
}
