package com.agenttravel.backend.adapter;

import java.util.List;

public interface WeatherAdapter {
    ProviderResponse<List<WeatherRecord>> forecast(WeatherRequest request);
}
