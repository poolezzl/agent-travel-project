package com.agenttravel.backend.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalPoiLookupControllerTests {

    private static final UUID POI_ID =
            UUID.fromString("8cdb7a2c-a88b-4529-99b2-9e74496c5bf4");
    private static final UUID SOURCE_ID =
            UUID.fromString("daa42808-dc0a-4152-a88a-e03c4af15b09");

    private KnowledgeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new InternalKnowledgeController(service, new PoiLookupRateLimiter(20)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void lookupReturnsFrozenResponseShape() throws Exception {
        when(service.lookupPoi(POI_ID.toString(), "beijing")).thenReturn(response("attraction"));

        mvc.perform(get("/internal/v1/knowledge/pois/{poiId}", POI_ID)
                        .param("city", "beijing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poi_id").value(POI_ID.toString()))
                .andExpect(jsonPath("$.city_id").value("beijing"))
                .andExpect(jsonPath("$.category").value("attraction"))
                .andExpect(jsonPath("$.latitude").value(39.916))
                .andExpect(jsonPath("$.longitude").value(116.397))
                .andExpect(jsonPath("$.coordinate_system").value("GCJ02"))
                .andExpect(jsonPath("$.data_at").exists())
                .andExpect(jsonPath("$.source_metadata[0].source_type").value("knowledge_base"))
                .andExpect(jsonPath("$.source_metadata[0].source_record_id").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.source_metadata[0].is_estimated").value(false));
    }

    @Test
    void lookupWithoutCityParamStillWorks() throws Exception {
        when(service.lookupPoi(POI_ID.toString(), null)).thenReturn(response("unknown"));

        mvc.perform(get("/internal/v1/knowledge/pois/{poiId}", POI_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("unknown"));
    }

    @Test
    void notFoundMapsToProblemDetail() throws Exception {
        when(service.lookupPoi(POI_ID.toString(), null)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "POI_NOT_FOUND", "POI 不存在或缺少权威坐标"));

        mvc.perform(get("/internal/v1/knowledge/pois/{poiId}", POI_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidIdMapsToBadRequest() throws Exception {
        when(service.lookupPoi("not-a-uuid", null)).thenThrow(
                new ApiException(HttpStatus.BAD_REQUEST, "POI_ID_INVALID", "POI ID 不合法"));

        mvc.perform(get("/internal/v1/knowledge/pois/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POI_ID_INVALID"));
    }

    @Test
    void rateLimiterRejectsSecondCallInSameWindow() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(
                        new InternalKnowledgeController(service, new PoiLookupRateLimiter(1)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(service.lookupPoi(POI_ID.toString(), null)).thenReturn(response("attraction"));

        mvc.perform(get("/internal/v1/knowledge/pois/{poiId}", POI_ID))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/knowledge/pois/{poiId}", POI_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(service).lookupPoi(POI_ID.toString(), null);
    }

    private static KnowledgeModels.PoiLookupResponse response(String category) {
        KnowledgeModels.SourceMetadata source = new KnowledgeModels.SourceMetadata(
                "knowledge_base", SOURCE_ID, Instant.parse("2026-11-24T03:59:00Z"), null,
                "fresh", false);
        return new KnowledgeModels.PoiLookupResponse(POI_ID, "beijing", "故宫博物院", category,
                39.916, 116.397, "GCJ02", Instant.parse("2026-11-24T04:00:00Z"),
                List.of(source), Instant.parse("2026-11-24T05:00:00Z"));
    }
}
