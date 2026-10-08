package com.agenttravel.backend.adapter;

import java.util.Objects;

public record PoiSearchRequest(String cityId, String query, String category, int page) {

    public PoiSearchRequest {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(category, "category");
        if (cityId.isBlank() || query.isBlank() || page < 1) {
            throw new IllegalArgumentException("POI 查询参数无效");
        }
    }
}
