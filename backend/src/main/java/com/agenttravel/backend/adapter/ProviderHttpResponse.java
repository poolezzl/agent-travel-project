package com.agenttravel.backend.adapter;

import java.util.Objects;

public record ProviderHttpResponse(int statusCode, String body) {

    public ProviderHttpResponse {
        Objects.requireNonNull(body, "body");
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("供应商响应状态码无效");
        }
    }
}
