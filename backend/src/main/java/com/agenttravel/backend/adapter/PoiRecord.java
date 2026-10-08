package com.agenttravel.backend.adapter;

import java.time.Instant;
import java.util.Objects;

public record PoiRecord(
        String poiId,
        String cityId,
        Coordinate coordinate,
        String coordinateSystem,
        String category,
        String name,
        Instant dataAt,
        ProviderSource source) {

    public PoiRecord {
        Objects.requireNonNull(poiId, "poiId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(coordinateSystem, "coordinateSystem");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dataAt, "dataAt");
        Objects.requireNonNull(source, "source");
        if (poiId.isBlank() || cityId.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("POI 标识和名称不能为空");
        }
        if (!coordinateSystem.equals("GCJ02") && !coordinateSystem.equals("WGS84")) {
            throw new IllegalArgumentException("不支持的坐标系");
        }
    }
}
