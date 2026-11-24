package com.agenttravel.backend.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Ownership, clamping and failure mapping for the S24 trip version read model. */
class TripVersionServiceTest {

    private final TripVersionRepository repository = mock(TripVersionRepository.class);
    private final TripVersionService service = new TripVersionService(repository, new ObjectMapper());

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID V2 = UUID.randomUUID();
    private static final UUID V1 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Test
    void listClampsPagingAndMarksCurrentVersion() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.listVersions(eq(TRIP), anyInt(), anyInt())).thenReturn(List.of(
                new TripVersionRepository.SummaryRow(V2, 2, V1, "PUBLISHED", "ETAG2", "HASH2", "s19-plan-v1", NOW,
                        "UPDATE"),
                new TripVersionRepository.SummaryRow(V1, 1, null, "SUPERSEDED", "ETAG1", "HASH1", "s19-plan-v1",
                        NOW, null)));

        var versions = service.list(OWNER, "USER", TRIP, 0, -5);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(2);
        assertThat(versions.get(0).current()).isTrue();
        assertThat(versions.get(0).changeType()).isEqualTo("UPDATE");
        assertThat(versions.get(1).parentVersionId()).isNull();
        assertThat(versions.get(1).current()).isFalse();
        verify(repository).listVersions(TRIP, 1, 0);
    }

    @Test
    void otherUsersTripIsReportedAsNotFound() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.list(OTHER, "USER", TRIP, 20, 0))
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo("CONTEXT_NOT_FOUND");
                });
        verify(repository, never()).listVersions(any(), anyInt(), anyInt());
    }

    @Test
    void adminMayReadForeignTrip() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.listVersions(TRIP, 20, 0)).thenReturn(List.of());

        assertThat(service.list(OTHER, "ADMIN", TRIP, 20, 0)).isEmpty();
    }

    @Test
    void missingVersionIsNotFound() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.findVersion(TRIP, V1)).thenReturn(null);

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.get(OWNER, "USER", TRIP, V1))
                .satisfies(exception -> assertThat(exception.code()).isEqualTo("CONTEXT_NOT_FOUND"));
    }

    @Test
    void versionWithoutSnapshotIsConflict() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.findVersion(TRIP, V2)).thenReturn(new TripVersionRepository.DetailRow(V2, 2, V1,
                "PUBLISHED", "ETAG2", "HASH2", "s19-plan-v1", NOW, "UPDATE", "[\"days[0].weather\"]", null, null));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.get(OWNER, "USER", TRIP, V2))
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("PLAN_NOT_READY");
                });
    }

    @Test
    void corruptedPlanIsReportedWithStableCode() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.findVersion(TRIP, V2)).thenReturn(new TripVersionRepository.DetailRow(V2, 2, V1,
                "PUBLISHED", "ETAG2", "HASH2", "s19-plan-v1", NOW, "UPDATE", null, "{not-json", "{}"));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.get(OWNER, "USER", TRIP, V2))
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.code()).isEqualTo("PLAN_CORRUPTED");
                });
    }

    @Test
    void detailExposesPlanSourcesAndDegradedFields() {
        when(repository.findTrip(TRIP)).thenReturn(new TripVersionRepository.TripRow(OWNER, V2));
        when(repository.findVersion(TRIP, V2)).thenReturn(new TripVersionRepository.DetailRow(V2, 2, V1,
                "PUBLISHED", "ETAG2", "HASH2", "s19-plan-v1", NOW, "UPDATE",
                "[\"days[0].weather\"]",
                "{\"days\":[{\"date\":\"2026-12-01\"}],\"sources\":[{\"kind\":\"weather\"}],"
                        + "\"degraded_fields\":[\"weather\"]}",
                "{\"day_count\":1}"));

        var detail = service.get(OWNER, "USER", TRIP, V2);

        assertThat(detail.current()).isTrue();
        assertThat(detail.affectedPaths()).containsExactly("days[0].weather");
        assertThat(detail.sourceMetadata()).hasSize(1);
        assertThat(detail.degradedFields()).containsExactly("weather");
        assertThat(detail.summary()).containsEntry("day_count", 1);
    }
}
