package com.agenttravel.backend.adapter;

public interface ProviderHttpTransport {

    ProviderHttpResponse send(ProviderHttpRequest request);
}
