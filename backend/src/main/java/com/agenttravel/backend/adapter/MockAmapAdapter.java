package com.agenttravel.backend.adapter;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MockAmapAdapter implements RouteAdapter, PoiAdapter {

    private final String alias;
    private final ProviderPolicy routePolicy;
    private final ProviderPolicy poiPolicy;
    private final Clock clock;
    private final ProviderCallExecutor calls;
    private final InMemoryProviderCache<RouteData> routeCache;
    private final InMemoryProviderCache<List<PoiRecord>> poiCache;

    public MockAmapAdapter(String alias, ProviderPolicy policy, Clock clock, ProviderCallExecutor calls,
            InMemoryProviderCache<RouteData> routeCache, InMemoryProviderCache<List<PoiRecord>> poiCache) {
        this(alias, policy, policy, clock, calls, routeCache, poiCache);
    }

    public MockAmapAdapter(String alias, ProviderPolicy routePolicy, ProviderPolicy poiPolicy, Clock clock,
            ProviderCallExecutor calls, InMemoryProviderCache<RouteData> routeCache,
            InMemoryProviderCache<List<PoiRecord>> poiCache) {
        this.alias = alias;
        this.routePolicy = routePolicy;
        this.poiPolicy = poiPolicy;
        this.clock = clock;
        this.calls = calls;
        this.routeCache = routeCache;
        this.poiCache = poiCache;
    }

    @Override
    public ProviderResponse<RouteData> route(RouteRequest request) {
        String key = AdapterCacheKeys.route(routePolicy.cacheVersion(), alias, request);
        Instant now = clock.instant();
        CacheLookup<RouteData> cached = routeCache.get(key, now);
        if (cached.present() && cached.fresh()) {
            if (cached.entry().negative()) {
                throw new ProviderException(ProviderKind.AMAP_ROUTE, cached.entry().negativeCode(), "路线无可用结果");
            }
            return cachedResponse(cached.entry(), now);
        }

        try {
            RouteData route = routeCache.singleFlight(key, () -> loadRoute(key, request));
            ProviderSource source = source("route", clock.instant(), true);
            return new ProviderResponse<>(route, source, false);
        } catch (ProviderException exception) {
            if (cached.present() && cached.stale() && !cached.entry().negative()) {
                routeCache.restore(key, cached.entry());
                return cachedResponse(cached.entry(), clock.instant()).asDegraded(exception.code());
            }
            throw exception;
        }
    }

    @Override
    public ProviderResponse<List<PoiRecord>> search(PoiSearchRequest request) {
        String key = AdapterCacheKeys.poi(poiPolicy.cacheVersion(), alias, request);
        Instant now = clock.instant();
        CacheLookup<List<PoiRecord>> cached = poiCache.get(key, now);
        if (cached.present() && cached.fresh()) {
            if (cached.entry().negative()) {
                throw new ProviderException(ProviderKind.AMAP_POI, cached.entry().negativeCode(), "POI 无可用结果");
            }
            return cachedResponse(cached.entry(), now);
        }

        try {
            List<PoiRecord> records = poiCache.singleFlight(key, () -> loadPois(key, request));
            ProviderSource source = source("poi", clock.instant(), true);
            return new ProviderResponse<>(records, source, false);
        } catch (ProviderException exception) {
            if (cached.present() && cached.stale() && !cached.entry().negative()) {
                poiCache.restore(key, cached.entry());
                return cachedResponse(cached.entry(), clock.instant()).asDegraded(exception.code());
            }
            throw exception;
        }
    }

    private RouteData loadRoute(String key, RouteRequest request) {
        Instant now = clock.instant();
        try {
            RouteData route = calls.call(ProviderKind.AMAP_ROUTE, () -> {
                if (request.mode().equalsIgnoreCase("empty")) {
                    throw new ProviderException(ProviderKind.AMAP_ROUTE, ProviderErrorCode.EMPTY_RESULT, "路线为空");
                }
                if (request.mode().equalsIgnoreCase("dirty")) {
                    throw new ProviderException(ProviderKind.AMAP_ROUTE, ProviderErrorCode.DIRTY_DATA, "路线数据脏损");
                }
                return buildRoute(request);
            });
            routeCache.put(key, route, source("route", now, true), now, routePolicy.cacheTtl());
            return route;
        } catch (ProviderException exception) {
            if (exception.code() == ProviderErrorCode.EMPTY_RESULT
                    || exception.code() == ProviderErrorCode.INVALID_RESPONSE
                    || exception.code() == ProviderErrorCode.DIRTY_DATA) {
                routeCache.putNegative(key, exception.code(), now, routePolicy.negativeCacheTtl());
            }
            throw exception;
        }
    }

    private List<PoiRecord> loadPois(String key, PoiSearchRequest request) {
        Instant now = clock.instant();
        try {
            List<PoiRecord> records = calls.call(ProviderKind.AMAP_POI, () -> buildPois(request, now));
            poiCache.put(key, records, source("poi", now, true), now, poiPolicy.cacheTtl());
            return records;
        } catch (ProviderException exception) {
            if (exception.code() == ProviderErrorCode.EMPTY_RESULT
                    || exception.code() == ProviderErrorCode.INVALID_RESPONSE
                    || exception.code() == ProviderErrorCode.DIRTY_DATA) {
                poiCache.putNegative(key, exception.code(), now, poiPolicy.negativeCacheTtl());
            }
            throw exception;
        }
    }

    private <T> ProviderResponse<T> cachedResponse(CacheEntry<T> entry, Instant now) {
        ProviderSource source = entry.source().refreshedStatus(now);
        return new ProviderResponse<>(entry.value(), source, true,
                source.freshnessStatus() != FreshnessStatus.FRESH, null);
    }

    private RouteData buildRoute(RouteRequest request) {
        int distance = Math.max(100, (int) Math.round(haversineMeters(request.origin(), request.destination())));
        int duration = Math.max(1, (int) Math.ceil(distance / 450.0));
        return new RouteData(request.origin(), request.destination(), request.mode(), distance, duration, true);
    }

    private List<PoiRecord> buildPois(PoiSearchRequest request, Instant now) {
        if (request.query().equalsIgnoreCase("empty")) {
            throw new ProviderException(ProviderKind.AMAP_POI, ProviderErrorCode.EMPTY_RESULT, "POI 为空");
        }
        if (request.query().equalsIgnoreCase("dirty")) {
            throw new ProviderException(ProviderKind.AMAP_POI, ProviderErrorCode.DIRTY_DATA, "POI 数据脏损");
        }
        ProviderSource source = source("poi-" + request.cityId(), now, true);
        String id = "mock-poi-" + UUID.nameUUIDFromBytes(
                (request.cityId() + "|" + request.query() + "|" + request.category()).getBytes(StandardCharsets.UTF_8));
        PoiRecord record = new PoiRecord(id, request.cityId(), new Coordinate(116.397, 39.909), "GCJ02",
                request.category(), request.query(), now, source);
        return List.of(record);
    }

    private ProviderSource source(String recordId, Instant retrievedAt, boolean estimated) {
        ProviderPolicy sourcePolicy = recordId.equals("route") ? routePolicy : poiPolicy;
        return new ProviderSource(alias, recordId, retrievedAt, retrievedAt.plus(sourcePolicy.cacheTtl()),
                FreshnessStatus.FRESH, estimated);
    }

    private double haversineMeters(Coordinate first, Coordinate second) {
        double earthRadius = 6_371_000;
        double lat1 = Math.toRadians(first.latitude());
        double lat2 = Math.toRadians(second.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(second.longitude() - first.longitude());
        double value = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }
}
