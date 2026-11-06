package com.agenttravel.backend.adapter;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ProviderHttpRequest(URI uri, Map<String, String> headers, Duration timeout) {

    public ProviderHttpRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(timeout, "timeout");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("供应商地址无效");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("供应商请求超时必须为正数");
        }
        headers = Map.copyOf(headers);
    }
}
