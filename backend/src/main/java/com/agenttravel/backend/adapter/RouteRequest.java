package com.agenttravel.backend.adapter;

import java.time.Instant;
import java.util.Objects;

public record RouteRequest(
        String coordinateSystem,
        Coordinate origin,
        Coordinate destination,
        String mode,
        Instant departureAt) {

    public RouteRequest {
        Objects.requireNonNull(coordinateSystem, "coordinateSystem");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(departureAt, "departureAt");
        if (!coordinateSystem.equals("GCJ02") && !coordinateSystem.equals("WGS84")) {
            throw new IllegalArgumentException("不支持的坐标系");
        }
        if (mode.isBlank()) {
            throw new IllegalArgumentException("出行方式不能为空");
        }
    }
}
