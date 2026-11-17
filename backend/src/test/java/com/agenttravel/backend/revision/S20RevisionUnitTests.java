package com.agenttravel.backend.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import org.junit.jupiter.api.Test;

class S20RevisionUnitTests {

    @Test
    void queryAndAmbiguousDoNotWriteVersion() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "AA");
        when(repository.findVersion(trip, baseId, user, false)).thenReturn(base);

        RevisionModels.RevisionResult query = service.preview(user, trip, command(baseId, "AA", "query", false, false,
                List.of(), List.of(), List.of(), null));
        RevisionModels.RevisionResult ambiguous = service.preview(user, trip,
                command(baseId, "AA", "ambiguous", false, false, List.of(), List.of(), List.of(), null));

        assertThat(query.versionId()).isNull();
        assertThat(query.needsConfirm()).isFalse();
        assertThat(ambiguous.clarificationQuestion()).isNotBlank();
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyList(), any());
    }

    @Test
    void confirmationRejectionLeavesOriginalVersionAndDoesNotCharge() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "BB");
        when(repository.findVersion(trip, baseId, user, true)).thenReturn(base);
        RevisionModels.RevisionResult result = service.confirm(user, trip,
                command(baseId, "BB", "change", true, false, List.of(2), List.of("route-planner"),
                        List.of("/days/2"), Map.of("days", List.of("原计划"))));

        assertThat(result.status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(result.versionId()).isNull();
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyList(), any());
    }

    @Test
    void oldEtagReturns409BeforeAnyWrite() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "CC");
        when(repository.findVersion(trip, baseId, user, true)).thenReturn(base);

        assertThatThrownBy(() -> service.confirm(user, trip,
                command(baseId, "OLD", "change", false, true, List.of(1), List.of("route-planner"),
                        List.of("/days/1"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("REVISION_BASE_MISMATCH");
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyList(), any());
    }

    @Test
    void successfulChangeCopiesPlanWhenOnlyWhitelistedBlockChanges() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "DD");
        when(repository.findVersion(trip, baseId, user, true)).thenReturn(base);
        when(repository.nextVersionNo(trip)).thenReturn(2);
        when(repository.json(any())).thenReturn("[]");
        UUID newVersion = UUID.randomUUID();
        when(repository.insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(newVersion);
        when(repository.advanceCurrentVersion(trip, baseId, newVersion, "DD")).thenReturn(1);

        RevisionModels.RevisionResult result = service.confirm(user, trip,
                command(baseId, "DD", "change", false, true, List.of(1), List.of("route-planner"),
                        List.of("/days/1/segments/2"), null));

        assertThat(result.versionId()).isEqualTo(newVersion);
        assertThat(result.parentVersionId()).isEqualTo(baseId);
        verify(repository).insertVersion(eq(trip), eq(baseId), eq(2), any(), any(), eq("revision-schema-v1"),
                eq("system"), eq("revision-v1"), eq(user), eq(base.planJson()), anyString(), anyString(), any(),
                eq("UPDATE"), eq(baseId), anyList(), any());
    }

    @Test
    void concurrentCasFailureReturns409() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "EE");
        when(repository.findVersion(trip, baseId, user, true)).thenReturn(base);
        when(repository.nextVersionNo(trip)).thenReturn(2);
        when(repository.json(any())).thenReturn("[]");
        UUID newVersion = UUID.randomUUID();
        when(repository.insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(newVersion);
        when(repository.advanceCurrentVersion(trip, baseId, newVersion, "EE")).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(user, trip,
                command(baseId, "EE", "change", false, true, List.of(1), List.of("route-planner"),
                        List.of("/days/1"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("REVISION_BASE_MISMATCH");
    }

    @Test
    void lockedPathRequiresConfirmation() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        when(repository.findVersion(trip, baseId, user, false)).thenReturn(version(baseId, trip, "FF"));

        RevisionModels.RevisionCommand command = new RevisionModels.RevisionCommand(baseId, "FF", "change",
                List.of("/days/1/locked"), List.of(1), List.of("route-planner"),
                List.of(new RevisionModels.AtomicChange("replace", "/days/1/locked", "新值")), false, false, null,
                "修改锁定项", null, null, null, null, null, null, List.of());

        assertThat(service.preview(user, trip, command).needsConfirm()).isTrue();
    }

    @Test
    void repeatedConfirmationReusesExistingVersion() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        RevisionRepository.VersionRow base = version(baseId, trip, "GG");
        RevisionRepository.VersionRow existing = new RevisionRepository.VersionRow(existingId, trip, baseId, 2, "HH",
                "HASH2", "PUBLISHED", "s20", "m1", "p1", Instant.now(), base.planJson());
        when(repository.findVersion(trip, baseId, user, true)).thenReturn(base);
        when(repository.json(any())).thenReturn("[]");
        when(repository.findVersionByChange(eq(trip), eq(baseId), eq(user), any())).thenReturn(existing);

        RevisionModels.RevisionResult result = service.confirm(user, trip,
                command(baseId, "GG", "change", false, true, List.of(1), List.of("route-planner"),
                        List.of("/days/1"), null));

        assertThat(result.versionId()).isEqualTo(existingId);
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void changeOutsideAffectedDaysIsRejected() {
        RevisionRepository repository = org.mockito.Mockito.mock(RevisionRepository.class);
        RevisionService service = new RevisionService(repository, org.mockito.Mockito.mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        when(repository.findVersion(trip, baseId, user, false)).thenReturn(version(baseId, trip, "II"));

        assertThatThrownBy(() -> service.preview(user, trip,
                command(baseId, "II", "change", false, false, List.of(1), List.of("route-planner"),
                        List.of("/days/2"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_INPUT");
    }

    private RevisionModels.RevisionCommand command(UUID baseId, String etag, String intent, boolean needsConfirm,
            boolean confirmed, List<Integer> days, List<String> agents, List<String> paths, Map<String, Object> plan) {
        List<RevisionModels.AtomicChange> changes = paths.stream()
                .map(path -> new RevisionModels.AtomicChange("replace", path, "新值")).toList();
        return new RevisionModels.RevisionCommand(baseId, etag, intent, List.of(), days, agents, changes, needsConfirm,
                confirmed, "confirm-1", "修改请求", plan, "答案", "请补充日期", null, null, null, List.of());
    }

    private RevisionRepository.VersionRow version(UUID id, UUID tripId, String etag) {
        return new RevisionRepository.VersionRow(id, tripId, null, 1, etag, "HASH", "PUBLISHED", "s19", "m1", "p1",
                Instant.now(), "{\"days\":[{\"id\":1},{\"id\":2}]}" );
    }
}
