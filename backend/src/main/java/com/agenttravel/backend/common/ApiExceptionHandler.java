package com.agenttravel.backend.common;

import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用");
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String detail) {
        String traceId = Optional.ofNullable(RequestContext.get()).map(RequestContext.Context::traceId)
                .orElse("unknown");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ApiError("https://agent-travel.local/problems/" + code,
                        status.getReasonPhrase(), status.value(), detail, code, traceId, Instant.now()));
    }
}
