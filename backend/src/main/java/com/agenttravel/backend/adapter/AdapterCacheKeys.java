package com.agenttravel.backend.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

final class AdapterCacheKeys {

    private static final String CACHE_NAMESPACE = "cache:v1:";

    private AdapterCacheKeys() {
    }

    static String route(String version, String alias, RouteRequest request) {
        String parameters = canonical(version, alias, request.coordinateSystem(), coordinate(request.origin()),
                coordinate(request.destination()), request.mode(), request.departureAt().toString());
        return CACHE_NAMESPACE + "route:" + safe(version) + ":" + safe(alias)
                + ":o" + digest(coordinate(request.origin()))
                + ":d" + digest(coordinate(request.destination()))
                + ":t" + digest(request.departureAt().truncatedTo(ChronoUnit.MINUTES).toString())
                + ":p" + digest(parameters);
    }

    static String poi(String version, String alias, PoiSearchRequest request) {
        String parameters = canonical(version, alias, request.cityId(),
                request.query().trim().toLowerCase(Locale.ROOT), request.category(), Integer.toString(request.page()));
        return CACHE_NAMESPACE + "poi:" + safe(version) + ":" + safe(alias)
                + ":i" + digest(canonical(request.cityId(), request.query(), request.category()))
                + ":p" + digest(parameters);
    }

    static String weather(String version, String alias, WeatherRequest request) {
        String parameters = canonical(version, alias, request.cityId(), request.from().toString(),
                request.to().toString(), Integer.toString(request.forecastHorizonDays()));
        return CACHE_NAMESPACE + "weather:" + safe(version) + ":" + safe(alias) + ":p" + digest(parameters);
    }

    private static String coordinate(Coordinate coordinate) {
        return "%.6f,%.6f".formatted(coordinate.longitude(), coordinate.latitude());
    }

    static String namespace(String version, String domain, String alias) {
        return domainNamespace(domain) + safe(version) + ":" + safe(alias) + ":";
    }

    static List<String> versionNamespaces(String version) {
        String versionSegment = safe(version) + ":";
        return List.of(domainNamespace("route") + versionSegment,
                domainNamespace("poi") + versionSegment,
                domainNamespace("weather") + versionSegment);
    }

    private static String domainNamespace(String domain) {
        return CACHE_NAMESPACE + domain + ":";
    }

    private static String canonical(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(value.length()).append(':').append(value);
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return digest(value);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hex.append("%02x".formatted(current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
