package com.agenttravel.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ApiExceptionHandlerTests {

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    void apiExceptionUsesProblemMediaType() {
        ResponseEntityCheck response = new ResponseEntityCheck(new ApiExceptionHandler().api(
                new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "不允许访问")));

        assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unexpectedExceptionUsesProblemMediaType() {
        ResponseEntityCheck response = new ResponseEntityCheck(new ApiExceptionHandler().unexpected(
                new IllegalStateException("test")));

        assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private record ResponseEntityCheck(MediaType contentType) {
        private ResponseEntityCheck(org.springframework.http.ResponseEntity<ApiError> response) {
            this(response.getHeaders().getContentType());
        }
    }
}
