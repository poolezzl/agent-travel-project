package com.agenttravel.backend.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.agenttravel.backend.common.ApiException;

class PreferenceServiceTests {

    private final FakeRepository repository = new FakeRepository();
    private final PreferenceService service = new PreferenceService(
            repository, new ObjectMapper().findAndRegisterModules(), new PreferenceProperties());
    private final UUID user = UUID.randomUUID();

    @Test
    void fewerThanThreeHistoriesProduceNoSummary() {
        repository.historyCount = 2;

        PreferenceModels.PreferenceSummary result = service.summary(user);

        assertThat(result.eligible()).isFalse();
        assertThat(result.sampleCount()).isEqualTo(2);
        assertThat(result.dimensions()).isEmpty();
        assertThat(result.narrative()).isEmpty();
        assertThat(repository.saved).isTrue();
        assertThat(repository.savedEligible).isFalse();
    }

    @Test
    void threeOrMoreHistoriesProduceSummaryWithDimensions() {
        repository.historyCount = 5;
        repository.cityCounts.add(new PreferenceRepository.CityCount("青岛", 4));
        repository.cityCounts.add(new PreferenceRepository.CityCount("厦门", 3));

        PreferenceModels.PreferenceSummary result = service.summary(user);

        assertThat(result.eligible()).isTrue();
        assertThat(result.sampleCount()).isEqualTo(5);
        assertThat(result.dimensions()).hasSize(1);
        assertThat(result.dimensions().get(0).key()).isEqualTo("city");
        assertThat(result.narrative()).contains("青岛");
        assertThat(repository.savedEligible).isTrue();
    }

    @Test
    void summaryProducesFiveDimensionsWithReasons() {
        repository.historyCount = 4;
        repository.cityCounts.add(new PreferenceRepository.CityCount("青岛", 3));
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子","美食"],"day_count":2,"budget":{"amount_minor":200000}},
                 "summary":{"point_count":3}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子","徒步"],"day_count":3,"budget":{"amount_minor":800000}},
                 "summary":{"point_count":15}}
                """, LocalDate.of(2026, 8, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-08-01T00:00:00Z")));

        PreferenceModels.PreferenceSummary result = service.summary(user);

        assertThat(result.dimensions()).extracting(PreferenceModels.PreferenceItem::key)
                .contains("city", "theme", "pace", "budget", "season");
        assertThat(result.dimensions()).allSatisfy(item ->
                assertThat(item.reason()).isNotBlank());
        PreferenceModels.PreferenceItem theme = result.dimensions().stream()
                .filter(item -> "theme".equals(item.key())).findFirst().orElseThrow();
        assertThat(String.valueOf(theme.valueJson())).contains("亲子");
        assertThat(theme.reason()).contains("主题");
    }

    @Test
    void clearMarksPreferencesCleared() {
        service.clear(user, "clear-key-1");
        assertThat(repository.cleared).isTrue();
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> service.clear(user, "  "))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void reuseRejectsTripOwnedByAnotherUser() {
        assertThatThrownBy(() -> service.reuse(user, UUID.randomUUID(), "reuse-key-missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void reuseRemovesSensitiveFieldsFromPlan() {
        UUID trip = UUID.randomUUID();
        repository.trip = new PreferenceRepository.TripRow(trip, "旧行程",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "ACTIVE", null);
        repository.version = new PreferenceRepository.VersionRow(UUID.randomUUID(),
                "{\"lodging\":{\"name\":\"某酒店\",\"address\":\"精确地址\"},\"privatePreferences\":[\"忌口\"],\"days\":[]}",
                "s24-v1", null, null);

        PreferenceModels.ReuseResult result = service.reuse(user, trip, "reuse-key-sensitive");

        assertThat(result.removedFields()).contains("lodging.address", "privatePreferences");
        assertThat(repository.insertedPlanJson).doesNotContain("精确地址");
        assertThat(repository.insertedPlanJson).doesNotContain("privatePreferences");
        assertThat(repository.insertedPlanJson).contains("某酒店");
    }

    @Test
    void sameKeyReturnsCachedResultWithoutSecondWrite() {
        UUID trip = UUID.randomUUID();
        repository.trip = new PreferenceRepository.TripRow(trip, "旧行程",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "ACTIVE", null);

        PreferenceModels.ReuseResult first = service.reuse(user, trip, "same-key");
        PreferenceModels.ReuseResult second = service.reuse(user, trip, "same-key");

        assertThat(second.tripId()).isEqualTo(first.tripId());
        assertThat(second.versionId()).isEqualTo(first.versionId());
        assertThat(repository.insertVersionCalls).isEqualTo(1);
    }

    private UUID prepareVersionedTrip() {
        UUID trip = UUID.randomUUID();
        repository.trip = new PreferenceRepository.TripRow(trip, "多版本行程",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "ACTIVE", null);
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        repository.versionMetas.add(new PreferenceRepository.VersionMetaRow(v1, 1, "DRAFT",
                Instant.parse("2026-11-20T10:00:00Z"), "s24-v1"));
        repository.versionMetas.add(new PreferenceRepository.VersionMetaRow(v2, 2, "ACTIVE",
                Instant.parse("2026-11-21T10:00:00Z"), "s24-v1"));
        repository.planJsonByVersion.put(v1,
                "{\"title\":\"初版\",\"days\":[{\"city\":\"青岛\",\"spots\":2}],\"budget\":3000}");
        repository.planJsonByVersion.put(v2,
                "{\"title\":\"终版\",\"days\":[{\"city\":\"青岛\",\"spots\":3}],\"budget\":3500,\"theme\":\"海滨\"}");
        return trip;
    }

    @Test
    void compareDetectsModifiedAddedAndRemovedFields() {
        UUID trip = prepareVersionedTrip();

        PreferenceModels.VersionComparison result = service.compare(user, trip, null, null);

        assertThat(result.from().versionNo()).isEqualTo(1);
        assertThat(result.to().versionNo()).isEqualTo(2);
        assertThat(result.changes()).extracting(PreferenceModels.VersionChange::path)
                .contains("title", "days[0].spots", "budget", "theme");
        assertThat(result.changes()).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("title");
            assertThat(change.changeType()).isEqualTo("MODIFIED");
            assertThat(change.before()).isEqualTo("初版");
            assertThat(change.after()).isEqualTo("终版");
        });
        assertThat(result.changes()).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("theme");
            assertThat(change.changeType()).isEqualTo("ADDED");
            assertThat(change.after()).isEqualTo("海滨");
        });
    }

    @Test
    void compareWithExplicitVersionsUsesRequestedRange() {
        UUID trip = prepareVersionedTrip();
        UUID v1 = repository.versionMetas.get(0).id();
        UUID v2 = repository.versionMetas.get(1).id();

        PreferenceModels.VersionComparison result = service.compare(user, trip, v1, v2);
        PreferenceModels.VersionComparison reversed = service.compare(user, trip, v2, v1);

        assertThat(result.changes()).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("theme");
            assertThat(change.changeType()).isEqualTo("ADDED");
        });
        assertThat(reversed.changes()).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("theme");
            assertThat(change.changeType()).isEqualTo("REMOVED");
        });
    }

    @Test
    void compareRejectsTripOwnedByAnotherUser() {
        assertThatThrownBy(() -> service.compare(user, UUID.randomUUID(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void compareRejectsVersionOutsideTrip() {
        UUID trip = prepareVersionedTrip();

        assertThatThrownBy(() -> service.compare(user, trip, UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("VERSION_NOT_FOUND"));
    }

    @Test
    void compareRejectsTripWithFewerThanTwoVersions() {
        UUID trip = UUID.randomUUID();
        repository.trip = new PreferenceRepository.TripRow(trip, "单版本行程",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "DRAFT", null);
        repository.versionMetas.add(new PreferenceRepository.VersionMetaRow(UUID.randomUUID(), 1,
                "DRAFT", Instant.now(), "s24-v1"));

        assertThatThrownBy(() -> service.compare(user, trip, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("VERSION_INSUFFICIENT"));
    }

    private static final class FakeRepository extends PreferenceRepository {
        int historyCount = 0;
        int nextVersion = 1;
        boolean saved = false;
        boolean savedEligible = false;
        boolean cleared = false;
        String insertedPlanJson = "";
        int insertVersionCalls = 0;
        List<PreferenceRepository.CityCount> cityCounts = new ArrayList<>();
        List<PreferenceRepository.PlanFact> planFacts = new ArrayList<>();
        PreferenceRepository.TripRow trip;
        PreferenceRepository.VersionRow version;
        List<PreferenceRepository.VersionMetaRow> versionMetas = new ArrayList<>();
        java.util.Map<UUID, String> planJsonByVersion = new java.util.HashMap<>();

        FakeRepository() {
            super(null);
        }

        @Override
        int countHistories(UUID user) {
            return historyCount;
        }

        @Override
        int nextSummaryVersion(UUID user) {
            return nextVersion;
        }

        @Override
        List<PreferenceRepository.CityCount> topCities(UUID user, int topK) {
            return cityCounts.stream().limit(topK).toList();
        }

        @Override
        List<PreferenceRepository.PlanFact> findLatestPlanFacts(UUID user) {
            return planFacts;
        }

        @Override
        List<PreferenceRepository.StatusCount> statusCounts(UUID user) {
            return List.of();
        }

        @Override
        void saveSummary(UUID user, int version, int sampleCount, boolean eligible,
                String summaryJson, String narrative, Instant now) {
            saved = true;
            savedEligible = eligible;
        }

        @Override
        String upsertPreference(UUID user, String key, String valueJson, String sourceType,
                double confidence, int weight, Instant now) {
            return UUID.randomUUID().toString();
        }

        @Override
        void insertEvidence(UUID preferenceId, String sourceType, UUID sourceRefId, byte[] evidenceHash,
                Instant observedAt, int weightDelta, String evidenceJson, Instant now) {
        }

        @Override
        List<PreferenceRepository.CityRef> cityRefs(UUID user, List<String> cities) {
            return List.of();
        }

        @Override
        List<PreferenceRepository.TripRef> statusRefs(UUID user) {
            return List.of();
        }

        @Override
        void clearPreferences(UUID user, Instant now) {
            cleared = true;
        }

        @Override
        TripRow findTrip(UUID owner, UUID tripId) {
            return trip != null && trip.id().equals(tripId) ? trip : null;
        }

        @Override
        VersionRow findCurrentVersion(UUID trip) {
            return version;
        }

        @Override
        List<VersionMetaRow> findVersionMetas(UUID owner, UUID trip) {
            return versionMetas;
        }

        @Override
        String findPlanJson(UUID owner, UUID trip, UUID version) {
            return planJsonByVersion.get(version);
        }

        @Override
        void copyTripAsDraft(UUID source, UUID target, UUID owner, String title,
                LocalDate startDate, LocalDate endDate, Instant now) {
        }

        @Override
        void insertVersion(UUID versionId, UUID trip, UUID owner, String planJson, String schemaVersion,
                String modelVersion, String promptVersion, Instant now) {
            insertedPlanJson = planJson;
            insertVersionCalls++;
        }
    }
}
