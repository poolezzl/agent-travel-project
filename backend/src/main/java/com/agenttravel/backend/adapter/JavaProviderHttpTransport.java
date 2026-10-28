package com.agenttravel.backend.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

public final class JavaProviderHttpTransport implements ProviderHttpTransport {

    private final HttpClient client;
    private final int maxResponseBytes;

    public JavaProviderHttpTransport(Duration connectTimeout, int maxResponseBytes) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("连接超时必须为正数");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("响应大小上限必须为正数");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public ProviderHttpResponse send(ProviderHttpRequest request) {
        Objects.requireNonNull(request, "request");
        requireHttps(request.uri());

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .GET()
                .timeout(request.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "agent-travel-backend/1.0");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        try {
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new ProviderHttpResponse(response.statusCode(), readBody(response.body()));
        } catch (HttpTimeoutException exception) {
            throw new ProviderTransportException(ProviderErrorCode.TIMEOUT, "供应商请求超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderTransportException(
                    ProviderErrorCode.UNAVAILABLE, "供应商请求被中断", exception);
        } catch (IOException exception) {
            throw new ProviderTransportException(
                    ProviderErrorCode.UNAVAILABLE, "供应商网络请求失败", exception);
        }
    }

    private String readBody(InputStream input) {
        if (input == null) {
            return "";
        }
        try (input) {
            byte[] bytes = input.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new ProviderTransportException(
                        ProviderErrorCode.INVALID_RESPONSE, "供应商响应超过大小上限", null);
            }
            return new String(decodeBody(bytes, maxResponseBytes), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ProviderTransportException(
                    ProviderErrorCode.UNAVAILABLE, "读取供应商响应失败", exception);
        }
    }

    static byte[] decodeBody(byte[] body, int maxDecodedBytes) {
        if (body.length < 2 || (body[0] & 0xff) != 0x1f || (body[1] & 0xff) != 0x8b) {
            return body;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] decoded = gzip.readNBytes(maxDecodedBytes + 1);
            if (decoded.length > maxDecodedBytes) {
                throw new ProviderTransportException(
                        ProviderErrorCode.INVALID_RESPONSE, "供应商解压响应超过大小上限", null);
            }
            return decoded;
        } catch (IOException exception) {
            throw new ProviderTransportException(
                    ProviderErrorCode.INVALID_RESPONSE, "供应商压缩响应无效", exception);
        }
    }

    private void requireHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ProviderTransportException(
                    ProviderErrorCode.INVALID_REQUEST, "生产供应商地址必须使用 HTTPS", null);
        }
    }
}
