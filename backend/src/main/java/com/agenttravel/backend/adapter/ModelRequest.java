package com.agenttravel.backend.adapter;

import java.util.Objects;

public record ModelRequest(String modelAlias, String inputData) {

    public ModelRequest {
        Objects.requireNonNull(modelAlias, "modelAlias");
        Objects.requireNonNull(inputData, "inputData");
        if (modelAlias.isBlank() || inputData.isBlank()) {
            throw new IllegalArgumentException("模型请求不能为空");
        }
    }
}
