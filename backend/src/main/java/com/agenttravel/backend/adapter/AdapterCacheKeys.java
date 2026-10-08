package com.agenttravel.backend.adapter;

import java.util.Locale;

final class AdapterCacheKeys {

    private AdapterCacheKeys() {
    }

    static String route(String version, String alias, RouteRequest request) {
        return String.join("|", "route", version, alias, request.coordinateSystem(),
                coordinate(request.origin()), coordinate(request.destination()), request.mode(),
                request.departureAt().toString());
    }

    static String poi(String version, String alias, PoiSearchRequest request) {
        return String.join("|", "poi", version, alias, request.cityId(),
                request.query().trim().toLowerCase(Locale.ROOT), request.category(), Integer.toString(request.page()));
    }

    static String weather(String version, String alias, WeatherRequest request) {
        return String.join("|", "weather", version, alias, request.cityId(), request.from().toString(),
                request.to().toString(), Integer.toString(request.forecastHorizonDays()));
    }

    private static String coordinate(Coordinate coordinate) {
        return "%.6f,%.6f".formatted(coordinate.longitude(), coordinate.latitude());
    }
}
