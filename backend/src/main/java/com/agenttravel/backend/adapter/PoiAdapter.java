package com.agenttravel.backend.adapter;

import java.util.List;

public interface PoiAdapter {
    ProviderResponse<List<PoiRecord>> search(PoiSearchRequest request);
}
