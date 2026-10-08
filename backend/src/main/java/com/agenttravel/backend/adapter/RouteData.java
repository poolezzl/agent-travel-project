package com.agenttravel.backend.adapter;

public record RouteData(
        Coordinate origin,
        Coordinate destination,
        String mode,
        int distanceMeters,
        int durationMinutes,
        boolean estimated) {

    public RouteData {
        if (distanceMeters <= 0 || durationMinutes <= 0) {
            throw new IllegalArgumentException("路线距离和时长必须为正数");
        }
    }
}
