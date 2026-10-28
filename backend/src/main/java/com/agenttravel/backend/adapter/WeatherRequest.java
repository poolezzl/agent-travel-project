package com.agenttravel.backend.adapter;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

public record WeatherRequest(String cityId, LocalDate from, LocalDate to, int forecastHorizonDays) {

    public WeatherRequest {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (cityId.isBlank() || to.isBefore(from) || forecastHorizonDays < 0) {
            throw new IllegalArgumentException("天气查询参数无效");
        }
        cityId = cityId.trim().toLowerCase(Locale.ROOT);
    }
}
