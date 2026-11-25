package com.agenttravel.backend.preference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ALG-12 偏好记忆参数。
 *
 * <p>所有数值来自 {@code app.preferences.*} 配置，代码中不得再写默认值以外的口径。
 * 当前默认值来自 {@code docs/alg-12-parameter-decision.md}，状态为“已配置化，待 R1/R3 会签”，尚未定稿；
 * 会签结论落地时只改本类默认值与 {@code application.yml}，不改调用方。
 */
@ConfigurationProperties(prefix = "app.preferences")
public class PreferenceProperties {

    /** 契约上界：recall 接口的 top_k 参数不得超过该值。 */
    public static final int CONTRACT_TOP_K_MAX = 20;

    /** 当前实现支持的统计维度；维度清单只能取这些值，未知值会导致启动失败。 */
    public static final List<String> SUPPORTED_DIMENSIONS =
            List.of("city", "theme", "pace", "budget", "season");

    private int topK = 5;
    private int topKMax = 20;
    private int cityTopN = 5;
    private double minConfidence = 0.6;
    private int weightIncrement = 3;
    private int weightCap = 100;
    private boolean decayEnabled = true;
    private int decayHalfLifeDays = 180;
    private int decayFloor = 1;
    private String ruleVersion = "s24-alg12-v1";
    private List<String> dimensions = List.of("city", "theme", "pace", "budget", "season");
    private double defaultConfidence = 0.7;
    private Map<String, Double> dimensionConfidence = new LinkedHashMap<>(Map.of("city", 0.8));
    private Pace pace = new Pace();
    private Budget budget = new Budget();

    /** 行程节奏分档阈值：每日景点数小于等于 relaxed 为轻松，介于两者之间为适中。 */
    public static class Pace {
        private double relaxedMaxPointsPerDay = 2;
        private double moderateMaxPointsPerDay = 4;

        public double getRelaxedMaxPointsPerDay() {
            return relaxedMaxPointsPerDay;
        }

        public void setRelaxedMaxPointsPerDay(double relaxedMaxPointsPerDay) {
            this.relaxedMaxPointsPerDay = relaxedMaxPointsPerDay;
        }

        public double getModerateMaxPointsPerDay() {
            return moderateMaxPointsPerDay;
        }

        public void setModerateMaxPointsPerDay(double moderateMaxPointsPerDay) {
            this.moderateMaxPointsPerDay = moderateMaxPointsPerDay;
        }
    }

    /** 预算档位阈值：预算金额小于等于 economyMaxYuan 为经济，介于两者之间为舒适。 */
    public static class Budget {
        private double economyMaxYuan = 1000;
        private double comfortMaxYuan = 5000;

        public double getEconomyMaxYuan() {
            return economyMaxYuan;
        }

        public void setEconomyMaxYuan(double economyMaxYuan) {
            this.economyMaxYuan = economyMaxYuan;
        }

        public double getComfortMaxYuan() {
            return comfortMaxYuan;
        }

        public void setComfortMaxYuan(double comfortMaxYuan) {
            this.comfortMaxYuan = comfortMaxYuan;
        }
    }

    /**
     * 启动即校验参数，避免错误配置静默生效。任一项不合法直接抛异常，使进程启动失败。
     */
    @PostConstruct
    public void validate() {
        require(topK >= 1 && topK <= CONTRACT_TOP_K_MAX,
                "app.preferences.top-k 必须在 1.." + CONTRACT_TOP_K_MAX + " 之间");
        require(topKMax >= 1 && topKMax <= CONTRACT_TOP_K_MAX,
                "app.preferences.top-k-max 必须在 1.." + CONTRACT_TOP_K_MAX + " 之间");
        require(topK <= topKMax, "app.preferences.top-k 不能大于 app.preferences.top-k-max");
        require(cityTopN >= 1 && cityTopN <= topKMax,
                "app.preferences.city-top-n 必须在 1..top-k-max 之间");
        require(minConfidence >= 0 && minConfidence <= 1,
                "app.preferences.min-confidence 必须在 0..1 之间");
        require(weightIncrement >= 1, "app.preferences.weight-increment 必须大于等于 1");
        require(weightCap >= 1, "app.preferences.weight-cap 必须大于等于 1");
        require(!decayEnabled || decayHalfLifeDays >= 1,
                "app.preferences.decay-half-life-days 必须大于等于 1");
        require(decayFloor >= 0, "app.preferences.decay-floor 不能为负数");
        require(dimensions != null && !dimensions.isEmpty(),
                "app.preferences.dimensions 不能为空");
        for (String dimension : dimensions) {
            require(SUPPORTED_DIMENSIONS.contains(dimension),
                    "app.preferences.dimensions 含不支持的维度：" + dimension
                            + "，可选值 " + SUPPORTED_DIMENSIONS);
        }
        require(defaultConfidence >= 0 && defaultConfidence <= 1,
                "app.preferences.default-confidence 必须在 0..1 之间");
        if (dimensionConfidence != null) {
            for (Map.Entry<String, Double> entry : dimensionConfidence.entrySet()) {
                require(SUPPORTED_DIMENSIONS.contains(entry.getKey()),
                        "app.preferences.dimension-confidence 含不支持的维度：" + entry.getKey());
                double value = entry.getValue() == null ? Double.NaN : entry.getValue();
                require(value >= 0 && value <= 1,
                        "app.preferences.dimension-confidence." + entry.getKey() + " 必须在 0..1 之间");
            }
        }
        require(pace.getRelaxedMaxPointsPerDay() > 0,
                "app.preferences.classification.pace.relaxed-max-points-per-day 必须大于 0");
        require(pace.getModerateMaxPointsPerDay() > pace.getRelaxedMaxPointsPerDay(),
                "app.preferences.classification.pace.moderate-max-points-per-day 必须大于 relaxed 阈值");
        require(budget.getEconomyMaxYuan() >= 0,
                "app.preferences.classification.budget.economy-max-yuan 不能为负数");
        require(budget.getComfortMaxYuan() > budget.getEconomyMaxYuan(),
                "app.preferences.classification.budget.comfort-max-yuan 必须大于 economy 阈值");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 取维度基准置信度；未单独配置时回落到 default-confidence。 */
    public double confidenceOf(String dimension) {
        if (dimensionConfidence != null) {
            Double override = dimensionConfidence.get(dimension);
            if (override != null) {
                return override;
            }
        }
        return defaultConfidence;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getTopKMax() {
        return topKMax;
    }

    public void setTopKMax(int topKMax) {
        this.topKMax = topKMax;
    }

    public int getCityTopN() {
        return cityTopN;
    }

    public void setCityTopN(int cityTopN) {
        this.cityTopN = cityTopN;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public int getWeightIncrement() {
        return weightIncrement;
    }

    public void setWeightIncrement(int weightIncrement) {
        this.weightIncrement = weightIncrement;
    }

    public int getWeightCap() {
        return weightCap;
    }

    public void setWeightCap(int weightCap) {
        this.weightCap = weightCap;
    }

    public boolean isDecayEnabled() {
        return decayEnabled;
    }

    public void setDecayEnabled(boolean decayEnabled) {
        this.decayEnabled = decayEnabled;
    }

    public int getDecayHalfLifeDays() {
        return decayHalfLifeDays;
    }

    public void setDecayHalfLifeDays(int decayHalfLifeDays) {
        this.decayHalfLifeDays = decayHalfLifeDays;
    }

    public int getDecayFloor() {
        return decayFloor;
    }

    public void setDecayFloor(int decayFloor) {
        this.decayFloor = decayFloor;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public List<String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions;
    }

    public double getDefaultConfidence() {
        return defaultConfidence;
    }

    public void setDefaultConfidence(double defaultConfidence) {
        this.defaultConfidence = defaultConfidence;
    }

    public Map<String, Double> getDimensionConfidence() {
        return dimensionConfidence;
    }

    public void setDimensionConfidence(Map<String, Double> dimensionConfidence) {
        this.dimensionConfidence = dimensionConfidence;
    }

    public Pace getPace() {
        return pace;
    }

    public void setPace(Pace pace) {
        this.pace = pace;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }
}
