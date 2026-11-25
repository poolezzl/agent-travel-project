package com.agenttravel.backend.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * ALG-12 参数配置化验收：默认值与决策单一致，且改动配置能改变召回结果。
 * 参数默认值来源：docs/alg-12-parameter-decision.md（已配置化，待 R1/R3 会签）。
 */
class PreferenceParameterTests {

    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final double DAY_MILLIS = 86_400_000d;

    private final FakePreferenceRepository repository = new FakePreferenceRepository();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UUID user = UUID.randomUUID();

    private PreferenceService service(PreferenceProperties properties) {
        return new PreferenceService(repository, json, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PreferenceProperties properties() {
        PreferenceProperties properties = new PreferenceProperties();
        properties.validate();
        return properties;
    }

    private void seedPreference(String key, double confidence, int weight, Instant lastUsedAt) {
        repository.activePreferences.add(new PreferenceRepository.PreferenceItemRow(key,
                "{\"value\":1}", "HISTORY_ANALYSIS", confidence, weight, lastUsedAt, 0d));
    }

    private void seedPreferenceWithEvidence(String key, double confidence, int weight, Instant lastUsedAt,
            double evidenceWeight) {
        repository.activePreferences.add(new PreferenceRepository.PreferenceItemRow(key,
                "{\"value\":1}", "HISTORY_ANALYSIS", confidence, weight, lastUsedAt, evidenceWeight));
    }

    @Test
    void defaultParametersMatchDecisionSheet() {
        PreferenceProperties properties = properties();

        assertThat(properties.getTopK()).isEqualTo(5);
        assertThat(properties.getTopKMax()).isEqualTo(20);
        assertThat(properties.getCityTopN()).isEqualTo(5);
        assertThat(properties.getMinConfidence()).isEqualTo(0.6);
        assertThat(properties.getWeightIncrement()).isEqualTo(3);
        assertThat(properties.getWeightCap()).isEqualTo(100);
        assertThat(properties.isDecayEnabled()).isTrue();
        assertThat(properties.getDecayHalfLifeDays()).isEqualTo(180);
        assertThat(properties.getDecayFloor()).isEqualTo(1);
        assertThat(properties.getRuleVersion()).isEqualTo("s24-alg12-v1");
        assertThat(properties.getDimensions())
                .containsExactly("city", "theme", "pace", "budget", "season");
        assertThat(properties.getDefaultConfidence()).isEqualTo(0.7);
        assertThat(properties.getDimensionConfidence()).containsEntry("city", 0.8);
        assertThat(properties.getPace().getRelaxedMaxPointsPerDay()).isEqualTo(2);
        assertThat(properties.getPace().getModerateMaxPointsPerDay()).isEqualTo(4);
        assertThat(properties.getBudget().getEconomyMaxYuan()).isEqualTo(1000);
        assertThat(properties.getBudget().getComfortMaxYuan()).isEqualTo(5000);
        assertThat(properties.confidenceOf("city")).isEqualTo(0.8);
        assertThat(properties.confidenceOf("theme")).isEqualTo(0.7);
    }

    @Test
    void configuredTopKLimitsRecallResult() {
        for (int index = 0; index < 6; index++) {
            seedPreference("city.city" + index, 0.9, 10 - index, NOW);
        }

        assertThat(service(properties()).recalledPreferences(user, null)).hasSize(5);

        PreferenceProperties narrow = properties();
        narrow.setTopK(2);
        assertThat(service(narrow).recalledPreferences(user, null)).hasSize(2);

        PreferenceProperties capped = properties();
        capped.setTopKMax(3);
        assertThat(service(capped).recalledPreferences(user, 10)).hasSize(3);
    }

    @Test
    void configuredMinConfidenceFiltersLowConfidencePreferences() {
        seedPreference("theme.亲子", 0.9, 30, NOW);
        seedPreference("theme.美食", 0.5, 20, NOW);

        assertThat(service(properties()).recalledPreferences(user, null))
                .extracting(PreferenceModels.PreferenceItem::key)
                .containsExactly("theme.亲子");

        PreferenceProperties lenient = properties();
        lenient.setMinConfidence(0.4);
        assertThat(service(lenient).recalledPreferences(user, null)).hasSize(2);
    }

    @Test
    void configuredHalfLifeChangesRecallOrderAndWeight() {
        seedPreference("theme.老偏好", 0.9, 30, NOW.minusMillis((long) (400 * DAY_MILLIS)));
        seedPreference("theme.新偏好", 0.9, 20, NOW);

        List<PreferenceModels.PreferenceItem> decayed = service(properties()).recalledPreferences(user, null);
        assertThat(decayed).extracting(PreferenceModels.PreferenceItem::key)
                .containsExactly("theme.新偏好", "theme.老偏好");
        assertThat(decayed.get(1).weight()).isEqualTo(6);

        PreferenceProperties noDecay = properties();
        noDecay.setDecayEnabled(false);
        List<PreferenceModels.PreferenceItem> raw = service(noDecay).recalledPreferences(user, null);
        assertThat(raw).extracting(PreferenceModels.PreferenceItem::key)
                .containsExactly("theme.老偏好", "theme.新偏好");
        assertThat(raw.get(0).weight()).isEqualTo(30);
    }

    @Test
    void configuredDecayFloorKeepsStalePreferenceVisible() {
        seedPreference("theme.很久以前", 0.9, 2, NOW.minusMillis((long) (1000 * DAY_MILLIS)));

        assertThat(service(properties()).recalledPreferences(user, null).get(0).weight()).isEqualTo(1);

        PreferenceProperties noFloor = properties();
        noFloor.setDecayFloor(0);
        assertThat(service(noFloor).recalledPreferences(user, null).get(0).weight()).isEqualTo(0);
    }

    @Test
    void configuredWeightIncrementAndCapChangeStoredWeight() {
        repository.historyCount = 3;
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子","亲子","美食"],"day_count":2,"budget":{"amount_minor":200000}},
                 "summary":{"point_count":3}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));

        PreferenceModels.PreferenceItem theme = service(properties()).summary(user).dimensions().stream()
                .filter(item -> "theme".equals(item.key())).findFirst().orElseThrow();
        assertThat(theme.weight()).isEqualTo(6);
        assertThat(theme.confidence()).isEqualTo(0.7);

        PreferenceProperties bigger = properties();
        bigger.setWeightIncrement(5);
        PreferenceModels.PreferenceItem biggerTheme = service(bigger).summary(user).dimensions().stream()
                .filter(item -> "theme".equals(item.key())).findFirst().orElseThrow();
        assertThat(biggerTheme.weight()).isEqualTo(10);

        PreferenceProperties capped = properties();
        capped.setWeightCap(4);
        PreferenceModels.PreferenceItem cappedTheme = service(capped).summary(user).dimensions().stream()
                .filter(item -> "theme".equals(item.key())).findFirst().orElseThrow();
        assertThat(cappedTheme.weight()).isEqualTo(4);
    }

    @Test
    void repeatedThemeValueIsCountedOncePerOccurrence() {
        repository.historyCount = 3;
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子","亲子","美食"],"day_count":2,"budget":{"amount_minor":200000}},
                 "summary":{"point_count":3}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));

        PreferenceModels.PreferenceItem theme = service(properties()).summary(user).dimensions().stream()
                .filter(item -> "theme".equals(item.key())).findFirst().orElseThrow();
        assertThat(String.valueOf(theme.valueJson())).contains("\"亲子\":2").contains("\"美食\":1");
        assertThat(theme.weight()).isEqualTo(6);
    }

    @Test
    void configuredDimensionsControlSummaryDimensions() {
        repository.historyCount = 3;
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子"],"day_count":2,"budget":{"amount_minor":200000}},
                 "summary":{"point_count":3}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));
        repository.cityCounts.add(new PreferenceRepository.CityCount("青岛", 3));

        assertThat(service(properties()).summary(user).dimensions())
                .extracting(PreferenceModels.PreferenceItem::key)
                .containsExactly("city", "theme", "pace", "budget", "season");

        PreferenceProperties narrowed = properties();
        narrowed.setDimensions(List.of("city", "theme"));
        assertThat(service(narrowed).summary(user).dimensions())
                .extracting(PreferenceModels.PreferenceItem::key)
                .containsExactly("city", "theme");
    }

    @Test
    void configuredCityTopNLimitsCityValues() {
        repository.historyCount = 3;
        for (int index = 0; index < 6; index++) {
            repository.cityCounts.add(new PreferenceRepository.CityCount("城市" + index, 6 - index));
        }

        PreferenceModels.PreferenceItem city = service(properties()).summary(user).dimensions().stream()
                .filter(item -> "city".equals(item.key())).findFirst().orElseThrow();
        assertThat(String.valueOf(city.valueJson())).contains("城市4").doesNotContain("城市5");

        PreferenceProperties narrowed = properties();
        narrowed.setCityTopN(2);
        PreferenceModels.PreferenceItem narrowedCity = service(narrowed).summary(user).dimensions().stream()
                .filter(item -> "city".equals(item.key())).findFirst().orElseThrow();
        assertThat(String.valueOf(narrowedCity.valueJson())).contains("城市1").doesNotContain("城市2");
    }

    @Test
    void classificationThresholdsComeFromConfiguration() {
        repository.historyCount = 3;
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":[],"day_count":1,"budget":{"amount_minor":90000}},
                 "summary":{"point_count":2}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));

        assertThat(service(properties()).summary(user).dimensions())
                .extracting(PreferenceModels.PreferenceItem::key)
                .contains("pace", "budget");

        PreferenceProperties relaxed = properties();
        relaxed.getPace().setRelaxedMaxPointsPerDay(3);
        relaxed.getBudget().setEconomyMaxYuan(2000);
        assertThat(service(relaxed).summary(user).dimensions())
                .extracting(PreferenceModels.PreferenceItem::key)
                .contains("pace", "budget");
    }

    @Test
    void recalledItemKeepsStoredSourceType() {
        repository.activePreferences.add(new PreferenceRepository.PreferenceItemRow("pace.轻松",
                "{\"level\":\"relaxed\"}", "EXPLICIT", 0.95, 12, NOW, 0d));

        PreferenceModels.PreferenceItem item = service(properties()).recalledPreferences(user, null).get(0);
        assertThat(item.sourceType()).isEqualTo("EXPLICIT");
        assertThat(item.reason()).contains("节奏");
    }

    @Test
    void extractionWritesEvidenceLedgerWithConfiguredIncrement() {
        repository.historyCount = 3;
        repository.planFacts.add(new PreferenceRepository.PlanFact("""
                {"trip":{"preferences":["亲子","亲子","美食"],"day_count":2,"budget":{"amount_minor":200000}},
                 "summary":{"point_count":3}}
                """, LocalDate.of(2026, 5, 1), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-05-01T00:00:00Z")));

        service(properties()).summary(user);

        assertThat(repository.upsertedKeys).contains("theme.亲子", "theme.美食");
        assertThat(repository.evidenceDeltas).contains(6, 3);

        PreferenceProperties bigger = properties();
        bigger.setWeightIncrement(5);
        repository.evidenceDeltas.clear();
        service(bigger).summary(user);
        assertThat(repository.evidenceDeltas).contains(10, 5);
    }

    @Test
    void effectiveWeightPrefersEvidenceLedgerAndRespectsCap() {
        seedPreferenceWithEvidence("theme.亲子", 0.9, 30, NOW, 12.6);

        assertThat(service(properties()).recalledPreferences(user, null).get(0).weight()).isEqualTo(13);

        PreferenceProperties capped = properties();
        capped.setWeightCap(10);
        assertThat(service(capped).recalledPreferences(user, null).get(0).weight()).isEqualTo(10);
    }

    @Test
    void invalidParametersAreRejected() {
        PreferenceProperties topK = properties();
        topK.setTopK(0);
        assertThatThrownBy(topK::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.top-k");

        PreferenceProperties topKMax = properties();
        topKMax.setTopKMax(21);
        assertThatThrownBy(topKMax::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.top-k-max");

        PreferenceProperties confidence = properties();
        confidence.setMinConfidence(1.5);
        assertThatThrownBy(confidence::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.min-confidence");

        PreferenceProperties increment = properties();
        increment.setWeightIncrement(0);
        assertThatThrownBy(increment::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.weight-increment");

        PreferenceProperties dimensions = properties();
        dimensions.setDimensions(List.of("city", "hotel"));
        assertThatThrownBy(dimensions::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.dimensions");

        PreferenceProperties empty = properties();
        empty.setDimensions(List.of());
        assertThatThrownBy(empty::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.preferences.dimensions");

        PreferenceProperties pace = properties();
        pace.getPace().setModerateMaxPointsPerDay(1);
        assertThatThrownBy(pace::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moderate-max-points-per-day");

        PreferenceProperties budget = properties();
        budget.getBudget().setComfortMaxYuan(100);
        assertThatThrownBy(budget::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comfort-max-yuan");
    }

    private static final class FakePreferenceRepository extends PreferenceRepository {
        int historyCount = 0;
        List<PreferenceRepository.CityCount> cityCounts = new ArrayList<>();
        List<PreferenceRepository.PlanFact> planFacts = new ArrayList<>();
        List<PreferenceRepository.PreferenceItemRow> activePreferences = new ArrayList<>();
        List<String> upsertedKeys = new ArrayList<>();
        List<Integer> evidenceDeltas = new ArrayList<>();

        FakePreferenceRepository() {
            super(null);
        }

        @Override
        int countHistories(UUID user) {
            return historyCount;
        }

        @Override
        int nextSummaryVersion(UUID user) {
            return 1;
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
        }

        @Override
        String upsertPreference(UUID user, String key, String valueJson, String sourceType,
                double confidence, int weight, Instant now) {
            String id = UUID.randomUUID().toString();
            upsertedKeys.add(key);
            return id;
        }

        @Override
        void insertEvidence(UUID preferenceId, String sourceType, UUID sourceRefId, byte[] evidenceHash,
                Instant observedAt, int weightDelta, String evidenceJson, Instant now) {
            evidenceDeltas.add(weightDelta);
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
        List<PreferenceRepository.PreferenceItemRow> findActivePreferences(UUID user, int halfLifeDays,
                boolean decayEnabled) {
            return activePreferences;
        }
    }
}
