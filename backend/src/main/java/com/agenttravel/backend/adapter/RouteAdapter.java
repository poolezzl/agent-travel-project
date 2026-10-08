package com.agenttravel.backend.adapter;

public interface RouteAdapter {
    ProviderResponse<RouteData> route(RouteRequest request);
}
