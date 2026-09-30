package com.example.modelgateway.presentation;

import com.example.modelgateway.domain.CircuitOpenException;
import com.example.modelgateway.domain.ModelBusyException;
import com.example.modelgateway.domain.ModelGatewayTimeoutException;
import com.example.modelgateway.domain.PermanentModelException;
import com.example.modelgateway.domain.RequestCancelledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "请求参数不合法", "请求内容未通过校验");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList());
        return problem;
    }

    @ExceptionHandler(ModelBusyException.class)
    ProblemDetail busy(ModelBusyException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "模型并发已满", exception.getMessage());
    }

    @ExceptionHandler(CircuitOpenException.class)
    ProblemDetail circuitOpen(CircuitOpenException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "模型熔断器已打开", exception.getMessage());
    }

    @ExceptionHandler(ModelGatewayTimeoutException.class)
    ProblemDetail timeout(ModelGatewayTimeoutException exception) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, "请求超过总截止时间", exception.getMessage());
    }

    @ExceptionHandler(PermanentModelException.class)
    ProblemDetail permanentFailure(PermanentModelException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "模型拒绝请求", exception.getMessage());
    }

    @ExceptionHandler(RequestCancelledException.class)
    ProblemDetail cancelled(RequestCancelledException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "请求已取消", exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("about:blank"));
        return problem;
    }
}
