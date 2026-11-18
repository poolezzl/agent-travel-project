package com.agenttravel.backend.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class S20RevisionUnitTests {

    private static final String PLAN = "{\"days\":[{\"date\":\"2026-11-20\"},{\"date\":\"2026-11-21\"}]}";
    private final UUID user = UUID.randomUUID();
    private final UUID trip = UUID.randomUUID();
    private final UUID base = UUID.randomUUID();
    private final RevisionRepository repository = mock(RevisionRepository.class);
    private final RevisionAgentClient agent = mock(RevisionAgentClient.class);
    private final PointsService points = mock(PointsService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RevisionService service = new RevisionService(repository, mock(AuditRepository.class), agent, points,
            mapper);

    @BeforeEach
    void prepare() {
        when(repository.findVersion(trip, base, user, false)).thenReturn(new RevisionRepository.VersionRow(base, trip,
                null, 1, "AA", "HASH", "PUBLISHED", "s19-plan-v1", "m1", "p1", Instant.now(), PLAN));
        when(repository.currentVersionId(trip, user, false)).thenReturn(base);
        when(repository.currentVersionId(trip, user, true)).thenReturn(base);
    }

    @Test
    void queryUsesServerAnswerAndIgnoresClientPlanAndIntent() {
        when(agent.propose(eq(base), any(), anyString(), any()))
                .thenReturn(new RevisionAgentClient.Proposal("ok", "query", base.toString(), "版本内回答", null,
                        null, null, null));
        var result = service.preview(user, trip, request(null, false));
        assertThat(result.answer()).isEqualTo("版本内回答");
        assertThat(result.changes()).isEmpty();
        verifyNoInteractions(points);
    }

    @Test
    void refusalNeverAppliesOrReservesPoints() {
        stubChange(new RevisionAgentClient.Scope(List.of(2), List.of("route-planner")));
        var result = service.confirm(user, trip, request("AA", false));
        assertThat(result.needsConfirm()).isTrue();
        assertThat(result.versionId()).isNull();
        verifyNoInteractions(points);
    }

    @Test
    void staleEtagIsRejectedBeforeAgentCall() {
        assertThatThrownBy(() -> service.confirm(user, trip, request("OLD", true)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("REVISION_BASE_MISMATCH"));
        verifyNoInteractions(agent, points);
    }

    @Test
    void inconsistentScopeIsRejectedBeforePoints() {
        stubChange(new RevisionAgentClient.Scope(List.of(1), List.of("route-planner")));
        assertThatThrownBy(() -> service.confirm(user, trip, request("AA", true)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(points);
    }

    @Test
    void agentCannotPublishChangesOutsideItsDays() throws Exception {
        stubChange(new RevisionAgentClient.Scope(List.of(2), List.of("route-planner")));
        when(points.settleRevision(eq(user), eq(trip), anyString(), any())).thenAnswer(
                invocation -> invocation.<Supplier<?>>getArgument(3).get());
        when(agent.apply(any(), any())).thenReturn(mapper.readTree("""
                {"days":[{"date":"2026-11-20","name":"changed"},{"date":"2026-11-21"}]}
                """));
        assertThatThrownBy(() -> service.confirm(user, trip, request("AA", true)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("白名单外"));
    }

    private void stubChange(RevisionAgentClient.Scope scope) {
        when(agent.propose(eq(base), any(), anyString(), any()))
                .thenReturn(new RevisionAgentClient.Proposal("ok", "change", base.toString(), null, null, null, scope,
                        List.of(new RevisionModels.AtomicChange("remove", "/days/2/segments/1", null))));
    }

    private RevisionModels.RevisionCommand request(String etag, boolean confirmed) {
        return new RevisionModels.RevisionCommand(base, etag, "change", List.of(), List.of(1), List.of("weather"),
                List.of(), false, confirmed, "key-1", "删除第二天的中国国家博物馆", Map.of("untrusted", true),
                "伪造答案", null, null, null, null, List.of());
    }
}
