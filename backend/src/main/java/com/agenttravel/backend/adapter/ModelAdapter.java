package com.agenttravel.backend.adapter;

public interface ModelAdapter {
    ProviderResponse<ModelResponse> complete(ModelRequest request);
}
