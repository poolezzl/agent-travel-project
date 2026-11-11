package com.agenttravel.backend.knowledge;

import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal read-only API consumed by the Python agent service.
 *
 * <p>This endpoint deliberately ignores external role/identity headers: the caller is trusted only
 * because it is inside the backend network, and the Java caller controls access.
 */
@RestController
@Lazy
@RequestMapping("/internal/v1/knowledge")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class InternalKnowledgeController {

    private final KnowledgeService service;

    public InternalKnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping("/attractions/query")
    public KnowledgeModels.AttractionQueryResponse queryAttractions(
            @Valid @RequestBody KnowledgeModels.AttractionQueryRequest request) {
        return service.queryAttractions(request);
    }

    @PostMapping("/reservations/query")
    public KnowledgeModels.ReservationQueryResponse queryReservations(
            @Valid @RequestBody KnowledgeModels.ReservationQueryRequest request) {
        return service.queryReservations(request);
    }

    @GetMapping("/catalog")
    public Map<String, Object> publishedCatalog(@RequestParam String city) {
        return service.exportPublishedCatalog(city);
    }
}
