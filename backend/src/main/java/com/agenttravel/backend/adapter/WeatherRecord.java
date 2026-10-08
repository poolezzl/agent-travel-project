package com.agenttravel.backend.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record WeatherRecord(
        LocalDate date,
        double tempMin,
        double tempMax,
        Double precipProbability,
        String condition,
        String dataType,
        String sourceRecordId,
        Instant retrievedAt,
        Instant validUntil) {

    public WeatherRecord {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        if (!Double.isFinite(tempMin) || !Double.isFinite(tempMax) || tempMin > tempMax
                || condition.isBlank() || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("天气数据不完整或温度范围无效");
        }
        if (precipProbability != null
                && (!Double.isFinite(precipProbability) || precipProbability < 0 || precipProbability > 1)) {
            throw new IllegalArgumentException("降水概率超出范围");
        }
        if (!dataType.equals("forecast") && !dataType.equals("climate_statistics")) {
            throw new IllegalArgumentException("天气数据类型无效");
        }
    }
}
