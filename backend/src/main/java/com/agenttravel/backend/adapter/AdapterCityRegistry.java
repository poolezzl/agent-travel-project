package com.agenttravel.backend.adapter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class AdapterCityRegistry {

    private final Map<String, ResolvedCity> cities;

    public AdapterCityRegistry(Map<String, ProviderCityMappingProperties> configuredCities) {
        Objects.requireNonNull(configuredCities, "configuredCities");
        this.cities = new LinkedHashMap<>();
        configuredCities.forEach(this::add);
    }

    public ResolvedCity resolve(String cityId) {
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("标准城市键不能为空");
        }
        ResolvedCity city = cities.get(cityId.trim().toLowerCase(Locale.ROOT));
        if (city == null) {
            throw new IllegalArgumentException("未配置标准城市映射");
        }
        return city;
    }

    private void add(String cityId, ProviderCityMappingProperties mapping) {
        if (cityId == null || cityId.isBlank() || mapping == null) {
            throw new IllegalArgumentException("标准城市映射不能为空");
        }
        String key = cityId.trim().toLowerCase(Locale.ROOT);
        String amapAdcode = required(mapping.getAmapAdcode());
        String amapCitycode = required(mapping.getAmapCitycode());
        String qweatherLocation = required(mapping.getQweatherLocation());
        cities.put(key, new ResolvedCity(key, amapAdcode, amapCitycode, qweatherLocation));
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("标准城市映射字段不能为空");
        }
        return value.trim();
    }

    public record ResolvedCity(
            String cityKey, String amapAdcode, String amapCitycode, String qweatherLocation) {}
}
