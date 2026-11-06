package com.agenttravel.backend.adapter;

public record Coordinate(double longitude, double latitude) {

    public Coordinate {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("坐标超出有效范围");
        }
    }
}
