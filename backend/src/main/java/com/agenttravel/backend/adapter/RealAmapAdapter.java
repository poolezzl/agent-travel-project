package com.agenttravel.backend.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;

public final class RealAmapAdapter implements RouteAdapter, PoiAdapter {

    private static final Set<String> AUTH_FAILURE_CODES =
            Set.of("10001", "10005", "10006", "10007");
    private static final Set<String> QUOTA_FAILURE_CODES = Set.of("10003", "10044");
    private static final Set<String> RATE_FAILURE_CODES = Set.of("10004", "10021", "10029", "10041");
    private static final Set<String> INVALID_REQUEST_CODES = Set.of("20000", "20001", "20002", "20003");

    private final String alias;
    private final URI baseUri;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final ProviderPolicy routePolicy;
    private final ProviderPolicy poiPolicy;
    private final Clock clock;
    private final ProviderCallExecutor calls;
    private final ProviderHttpTransport transport;
    private final AdapterCityRegistry cityRegistry;
    private final InMemoryProviderCache<RouteData> routeCache;
    private final InMemoryProviderCache<List<PoiRecord>> poiCache;

    public RealAmapAdapter(
            String alias,
            URI baseUri,
            String apiKey,
            ProviderPolicy routePolicy,
            ProviderPolicy poiPolicy,
            Clock clock,
            ProviderCallExecutor calls,
            ProviderHttpTransport transport,
            AdapterCityRegistry cityRegistry,
            ObjectMapper mapper,
            InMemoryProviderCache<RouteData> routeCache,
            InMemoryProviderCache<List<PoiRecord>> poiCache) {
        this.alias = requireText(alias, "供应商别名");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.apiKey = requireText(apiKey, "高德服务端密钥");
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.poiPolicy = Objects.requireNonNull(poiPolicy, "poiPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cityRegistry = Objects.requireNonNull(cityRegistry, "cityRegistry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.routeCache = Objects.requireNonNull(routeCache, "routeCache");
        this.poiCache = Objects.requireNonNull(poiCache, "poiCache");
    }

    @Override
    public ProviderResponse<RouteData> route(RouteRequest request) {
        String key = AdapterCacheKeys.route(routePolicy.cacheVersion(), alias, request);
        Instant now = clock.instant();
        CacheLookup<RouteData> cached = routeCache.get(key, now);
        if (cached.present() && cached.fresh()) {
            if (cached.entry().negative()) {
                throw new ProviderException(
                        ProviderKind.AMAP_ROUTE, cached.entry().negativeCode(), "路线无可用结果");
            }
            return cachedRouteResponse(cached.entry(), now);
        }

        try {
            RouteData route =
                    routeCache.singleFlight(key, () -> loadRoute(key, request));
            return new ProviderResponse<>(
                    route, routeSource(route, request, clock.instant()), false);
        } catch (ProviderException exception) {
            if (cached.present() && cached.stale() && !cached.entry().negative()) {
                return cachedRouteResponse(cached.entry(), clock.instant())
                        .asDegraded(exception.code());
            }
            throw exception;
        }
    }

    @Override
    public ProviderResponse<List<PoiRecord>> search(PoiSearchRequest request) {
        AdapterCityRegistry.ResolvedCity city = resolveCity(request.cityId());
        String key = AdapterCacheKeys.poi(
                poiPolicy.cacheVersion(), alias, request, city.amapAdcode());
        Instant now = clock.instant();
        CacheLookup<List<PoiRecord>> cached = poiCache.get(key, now);
        if (cached.present() && cached.fresh()) {
            if (cached.entry().negative()) {
                throw new ProviderException(
                        ProviderKind.AMAP_POI, cached.entry().negativeCode(), "POI 无可用结果");
            }
            return cachedPoiResponse(cached.entry(), now);
        }

        try {
            PoiLoad load = poiCache.singleFlight(key, () -> loadPois(key, request, city));
            ProviderSource source =
                    poiResponseSource(request, city, load.records(), clock.instant());
            return new ProviderResponse<>(
                    load.records(), source, false, load.degraded(), load.degradationCode());
        } catch (ProviderException exception) {
            if (cached.present() && cached.stale() && !cached.entry().negative()) {
                return cachedPoiResponse(cached.entry(), clock.instant())
                        .asDegraded(exception.code());
            }
            throw exception;
        }
    }

    private RouteData loadRoute(String key, RouteRequest request) {
        if (!"GCJ02".equals(request.coordinateSystem())) {
            throw new ProviderException(
                    ProviderKind.AMAP_ROUTE,
                    ProviderErrorCode.INVALID_REQUEST,
                    "高德路线仅接受 GCJ02 坐标");
        }

        Instant now = clock.instant();
        URI uri = routeUri(request);
        try {
            RouteData route = calls.call(
                    ProviderKind.AMAP_ROUTE, () -> parseRoute(send(ProviderKind.AMAP_ROUTE, uri, request), request));
            routeCache.put(key, route, routeSource(route, request, now), now, routePolicy.cacheTtl());
            return route;
        } catch (ProviderException exception) {
            putRouteNegativeIfAllowed(key, exception.code(), now);
            throw exception;
        }
    }

    private PoiLoad loadPois(
            String key, PoiSearchRequest request, AdapterCityRegistry.ResolvedCity city) {
        Instant now = clock.instant();
        URI uri = poiUri(request, city);
        try {
            PoiLoad load =
                    calls.call(
                            ProviderKind.AMAP_POI,
                            () -> parsePois(send(ProviderKind.AMAP_POI, uri, request), request, city));
            if (!load.degraded()) {
                poiCache.put(
                        key,
                        load.records(),
                        poiResponseSource(request, city, load.records(), now),
                        now,
                        poiPolicy.cacheTtl());
            }
            return load;
        } catch (ProviderException exception) {
            putPoiNegativeIfAllowed(key, exception.code(), now);
            throw exception;
        }
    }

    private ProviderHttpResponse send(ProviderKind kind, URI uri, Object request) {
        try {
            return transport.send(
                    new ProviderHttpRequest(uri, Map.of(), requestPolicy(kind).timeout()));
        } catch (ProviderTransportException exception) {
            throw new ProviderException(kind, exception.code(), exception.getMessage(), exception);
        }
    }

    private RouteData parseRoute(ProviderHttpResponse response, RouteRequest request) {
        JsonNode root = parseSuccessResponse(ProviderKind.AMAP_ROUTE, response);
        JsonNode route = root.path("route");
        JsonNode paths = route.path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            throw new ProviderException(
                    ProviderKind.AMAP_ROUTE, ProviderErrorCode.EMPTY_RESULT, "高德路线为空");
        }

        JsonNode path = paths.get(0);
        int distance = positiveInt(path.path("distance"), ProviderKind.AMAP_ROUTE, "路线距离");
        int durationSeconds = positiveInt(path.path("duration"), ProviderKind.AMAP_ROUTE, "路线时长");
        Coordinate origin = coordinate(route.path("origin"), ProviderKind.AMAP_ROUTE);
        Coordinate destination = coordinate(route.path("destination"), ProviderKind.AMAP_ROUTE);
        if (!close(origin, request.origin()) || !close(destination, request.destination())) {
            throw new ProviderException(
                    ProviderKind.AMAP_ROUTE, ProviderErrorCode.DIRTY_DATA, "高德路线起终点与请求不一致");
        }
        int durationMinutes = Math.max(1, Math.toIntExact((durationSeconds + 59L) / 60L));
        return new RouteData(origin, destination, request.mode(), distance, durationMinutes, false);
    }

    private PoiLoad parsePois(
            ProviderHttpResponse response,
            PoiSearchRequest request,
            AdapterCityRegistry.ResolvedCity city) {
        JsonNode root = parseSuccessResponse(ProviderKind.AMAP_POI, response);
        JsonNode pois = root.path("pois");
        if (!pois.isArray()) {
            throw new ProviderException(
                    ProviderKind.AMAP_POI,
                    ProviderErrorCode.INVALID_RESPONSE,
                    "高德地点响应结构无效");
        }
        if (pois.isEmpty()) {
            throw new ProviderException(
                    ProviderKind.AMAP_POI, ProviderErrorCode.EMPTY_RESULT, "高德地点为空");
        }

        Instant now = clock.instant();
        Map<String, PoiRecord> unique = new LinkedHashMap<>();
        int dirtyCount = 0;
        for (JsonNode poi : pois) {
            PoiRecord record = parsePoi(poi, request, city, now);
            if (record == null) {
                dirtyCount++;
                continue;
            }
            if (unique.putIfAbsent(record.poiId(), record) != null) {
                dirtyCount++;
            }
        }
        if (unique.isEmpty()) {
            throw new ProviderException(
                    ProviderKind.AMAP_POI, ProviderErrorCode.DIRTY_DATA, "高德地点数据均不可用");
        }
        List<PoiRecord> records = List.copyOf(unique.values());
        return new PoiLoad(records, dirtyCount > 0, dirtyCount > 0 ? ProviderErrorCode.DIRTY_DATA : null);
    }

    private PoiRecord parsePoi(
            JsonNode poi, PoiSearchRequest request, AdapterCityRegistry.ResolvedCity city, Instant now) {
        String id = text(poi, "id");
        String name = text(poi, "name");
        String category = text(poi, "typecode");
        if (category.isBlank()) {
            category = text(poi, "type");
        }
        Coordinate coordinate = optionalCoordinate(poi.path("location"));
        if (id.isBlank()
                || name.isBlank()
                || category.isBlank()
                || coordinate == null
                || !matchesCity(city, poi)) {
            return null;
        }
        ProviderSource source = new ProviderSource(
                alias,
                "amap-poi:" + id,
                now,
                now.plus(poiPolicy.cacheTtl()),
                FreshnessStatus.FRESH,
                false);
        return new PoiRecord(id, city.cityKey(), coordinate, "GCJ02", category, name, now, source);
    }

    private JsonNode parseSuccessResponse(ProviderKind kind, ProviderHttpResponse response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ProviderException(kind, ProviderErrorCode.AUTHENTICATION_FAILED, "高德鉴权失败");
        }
        if (status == 429) {
            ProviderErrorCode code =
                    amapFailureCode(response.body()).orElse(ProviderErrorCode.PROVIDER_RATE_LIMITED);
            throw new ProviderException(kind, code, "高德返回 429");
        }
        if (status >= 500) {
            throw new ProviderException(kind, ProviderErrorCode.UNAVAILABLE, "高德服务暂不可用");
        }
        if (status < 200 || status >= 300) {
            throw new ProviderException(kind, ProviderErrorCode.INVALID_REQUEST, "高德拒绝了请求");
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception exception) {
            throw new ProviderException(
                    kind, ProviderErrorCode.INVALID_RESPONSE, "高德响应不是有效 JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new ProviderException(
                    kind, ProviderErrorCode.INVALID_RESPONSE, "高德响应结构无效");
        }
        if (!"1".equals(text(root, "status"))) {
            ProviderErrorCode code =
                    amapFailureCode(response.body()).orElse(ProviderErrorCode.INVALID_RESPONSE);
            throw new ProviderException(kind, code, "高德返回失败状态");
        }
        return root;
    }

    private java.util.Optional<ProviderErrorCode> amapFailureCode(String body) {
        if (body == null || body.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(body);
            String code = text(root, "infocode");
            if (AUTH_FAILURE_CODES.contains(code)) {
                return java.util.Optional.of(ProviderErrorCode.AUTHENTICATION_FAILED);
            }
            if (QUOTA_FAILURE_CODES.contains(code)) {
                return java.util.Optional.of(ProviderErrorCode.QUOTA_EXCEEDED);
            }
            if (RATE_FAILURE_CODES.contains(code)) {
                return java.util.Optional.of(ProviderErrorCode.PROVIDER_RATE_LIMITED);
            }
            if (INVALID_REQUEST_CODES.contains(code)) {
                return java.util.Optional.of(ProviderErrorCode.INVALID_REQUEST);
            }
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    private URI routeUri(RouteRequest request) {
        String mode = request.mode().trim().toLowerCase(Locale.ROOT);
        String path =
                switch (mode) {
                    case "driving", "taxi" -> "/v3/direction/driving";
                    case "walking" -> "/v3/direction/walking";
                    default ->
                            throw new ProviderException(
                                    ProviderKind.AMAP_ROUTE,
                                    ProviderErrorCode.INVALID_REQUEST,
                                    "高德暂不支持该交通方式");
                };
        return UriComponentsBuilder.fromUri(baseUri)
                .path(path)
                .queryParam("origin", coordinateText(request.origin()))
                .queryParam("destination", coordinateText(request.destination()))
                .queryParam("extensions", "base")
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();
    }

    private URI poiUri(PoiSearchRequest request, AdapterCityRegistry.ResolvedCity city) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUri(baseUri)
                        .path("/v5/place/text")
                        .queryParam("keywords", request.query())
                        .queryParam("city", city.amapAdcode())
                        .queryParam("city_limit", "true")
                        .queryParam("page_num", request.page())
                        .queryParam("page_size", 20)
                        .queryParam("key", apiKey);
        if (!request.category().isBlank()
                && !request.category().equalsIgnoreCase("any")
                && !request.category().equalsIgnoreCase("all")) {
            builder.queryParam("types", request.category());
        }
        return builder.build().encode().toUri();
    }

    private ProviderSource routeSource(RouteData route, RouteRequest request, Instant retrievedAt) {
        return new ProviderSource(
                alias,
                sourceId(
                        "amap-route",
                        request.coordinateSystem(),
                        coordinateText(route.origin()),
                        coordinateText(route.destination()),
                        route.mode(),
                        Integer.toString(route.distanceMeters()),
                        Integer.toString(route.durationMinutes())),
                retrievedAt,
                retrievedAt.plus(routePolicy.cacheTtl()),
                FreshnessStatus.FRESH,
                false);
    }

    private ProviderSource poiResponseSource(
            PoiSearchRequest request,
            AdapterCityRegistry.ResolvedCity city,
            List<PoiRecord> records,
            Instant retrievedAt) {
        List<String> values = new ArrayList<>();
        values.add("amap-poi-response");
        values.add(city.cityKey());
        values.add(city.amapAdcode());
        values.add(request.query());
        values.add(request.category());
        values.add(Integer.toString(request.page()));
        records.forEach(record -> values.add(record.poiId()));
        return new ProviderSource(
                alias,
                sourceId(values.toArray(String[]::new)),
                retrievedAt,
                retrievedAt.plus(poiPolicy.cacheTtl()),
                FreshnessStatus.FRESH,
                false);
    }

    private ProviderResponse<RouteData> cachedRouteResponse(CacheEntry<RouteData> entry, Instant now) {
        ProviderSource source = entry.source().refreshedStatus(now);
        return new ProviderResponse<>(
                entry.value(), source, true, source.freshnessStatus() != FreshnessStatus.FRESH, null);
    }

    private ProviderResponse<List<PoiRecord>> cachedPoiResponse(
            CacheEntry<List<PoiRecord>> entry, Instant now) {
        ProviderSource source = entry.source().refreshedStatus(now);
        List<PoiRecord> refreshed =
                entry.value().stream().map(record -> refreshPoi(record, now)).toList();
        return new ProviderResponse<>(
                refreshed, source, true, source.freshnessStatus() != FreshnessStatus.FRESH, null);
    }

    private PoiRecord refreshPoi(PoiRecord record, Instant now) {
        return new PoiRecord(
                record.poiId(),
                record.cityId(),
                record.coordinate(),
                record.coordinateSystem(),
                record.category(),
                record.name(),
                record.dataAt(),
                record.source().refreshedStatus(now));
    }

    private void putRouteNegativeIfAllowed(String key, ProviderErrorCode code, Instant now) {
        if (isNegativeCacheable(code)) {
            routeCache.putNegative(key, code, now, routePolicy.negativeCacheTtl());
        }
    }

    private void putPoiNegativeIfAllowed(String key, ProviderErrorCode code, Instant now) {
        if (isNegativeCacheable(code)) {
            poiCache.putNegative(key, code, now, poiPolicy.negativeCacheTtl());
        }
    }

    private boolean isNegativeCacheable(ProviderErrorCode code) {
        return code == ProviderErrorCode.EMPTY_RESULT
                || code == ProviderErrorCode.INVALID_RESPONSE
                || code == ProviderErrorCode.DIRTY_DATA;
    }

    private ProviderPolicy requestPolicy(ProviderKind kind) {
        return kind == ProviderKind.AMAP_ROUTE ? routePolicy : poiPolicy;
    }

    private int positiveInt(JsonNode node, ProviderKind kind, String field) {
        long value;
        try {
            value = Long.parseLong(node.asText());
        } catch (NumberFormatException exception) {
            throw new ProviderException(kind, ProviderErrorCode.DIRTY_DATA, field + "无效", exception);
        }
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new ProviderException(kind, ProviderErrorCode.DIRTY_DATA, field + "无效");
        }
        return (int) value;
    }

    private Coordinate coordinate(JsonNode node, ProviderKind kind) {
        Coordinate coordinate = optionalCoordinate(node);
        if (coordinate == null) {
            throw new ProviderException(kind, ProviderErrorCode.DIRTY_DATA, "高德坐标缺失或无效");
        }
        return coordinate;
    }

    private Coordinate optionalCoordinate(JsonNode node) {
        String value = node == null ? "" : node.asText("").trim();
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Coordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean close(Coordinate actual, Coordinate expected) {
        return Math.abs(actual.longitude() - expected.longitude()) <= 0.02
                && Math.abs(actual.latitude() - expected.latitude()) <= 0.02;
    }

    private boolean matchesCity(AdapterCityRegistry.ResolvedCity city, JsonNode poi) {
        String adcode = text(poi, "adcode");
        String citycode = text(poi, "citycode");
        return samePrefecture(city.amapAdcode(), adcode) || city.amapCitycode().equals(citycode);
    }

    private boolean samePrefecture(String expected, String actual) {
        if (expected == null || actual == null || expected.length() < 4 || actual.length() < 4) {
            return false;
        }
        int prefixLength = expected.endsWith("0000") ? 2 : 4;
        return expected.regionMatches(0, actual, 0, prefixLength);
    }

    private AdapterCityRegistry.ResolvedCity resolveCity(String cityId) {
        try {
            return cityRegistry.resolve(cityId);
        } catch (IllegalArgumentException exception) {
            throw new ProviderException(
                    ProviderKind.AMAP_POI,
                    ProviderErrorCode.INVALID_REQUEST,
                    "标准城市未配置高德映射",
                    exception);
        }
    }

    private String coordinateText(Coordinate coordinate) {
        return "%.6f,%.6f".formatted(coordinate.longitude(), coordinate.latitude());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String sourceId(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append("%02x".formatted(current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private record PoiLoad(List<PoiRecord> records, boolean degraded, ProviderErrorCode degradationCode) {

        private PoiLoad {
            records = List.copyOf(records);
            if (degraded != (degradationCode != null)) {
                throw new IllegalArgumentException("地点降级状态不一致");
            }
        }
    }
}
