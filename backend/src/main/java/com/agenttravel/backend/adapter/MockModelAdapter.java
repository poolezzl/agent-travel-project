package com.agenttravel.backend.adapter;

import java.time.Clock;
import java.time.Instant;

public final class MockModelAdapter implements ModelAdapter {

    private final String alias;
    private final ProviderCallExecutor calls;
    private final Clock clock;

    public MockModelAdapter(String alias, ProviderCallExecutor calls, Clock clock) {
        this.alias = alias;
        this.calls = calls;
        this.clock = clock;
    }

    @Override
    public ProviderResponse<ModelResponse> complete(ModelRequest request) {
        ModelResponse response = calls.call(ProviderKind.LLM, () -> {
            if (request.inputData().equalsIgnoreCase("empty")) {
                throw new ProviderException(ProviderKind.LLM, ProviderErrorCode.EMPTY_RESULT, "模型返回为空");
            }
            return new ModelResponse(request.modelAlias(), "{\"mock\":true,\"status\":\"accepted\"}");
        });
        Instant now = clock.instant();
        ProviderSource source = new ProviderSource(alias, "mock-model-response", now, null,
                FreshnessStatus.UNKNOWN, true);
        return new ProviderResponse<>(response, source, false);
    }
}
