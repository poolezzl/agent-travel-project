package com.agenttravel.backend.adapter;

import java.util.Objects;

public record ModelResponse(String modelAlias, String outputText) {

    public ModelResponse {
        Objects.requireNonNull(modelAlias, "modelAlias");
        Objects.requireNonNull(outputText, "outputText");
        if (modelAlias.isBlank() || outputText.isBlank()) {
            throw new IllegalArgumentException("模型响应不能为空");
        }
    }
}
