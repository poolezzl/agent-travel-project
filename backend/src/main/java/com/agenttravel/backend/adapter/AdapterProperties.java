package com.agenttravel.backend.adapter;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.adapters")
public class AdapterProperties {

    private String mode = "mock";
    private String amapAlias = "amap";
    private String qweatherAlias = "qweather";
    private String llmAlias = "llm-mock";
    private int timeoutMs = 1500;
    private int maxRetries = 1;
    private int retryBackoffMs = 20;
    private int circuitFailureThreshold = 3;
    private int circuitOpenSeconds = 10;
    private int rateLimitPerSecond = 20;
    private int quotaPerWindow = 1000;
    private int quotaWindowSeconds = 86400;
    private int routeCacheTtlSeconds = 604800;
    private int weatherCacheTtlSeconds = 3600;
    private int poiCacheTtlSeconds = 2592000;
    private int negativeCacheTtlSeconds = 15;
    private String cacheVersion = "v1";

    public ProviderPolicy policy() {
        return policy(Duration.ofSeconds(routeCacheTtlSeconds));
    }

    public ProviderPolicy policy(Duration cacheTtl) {
        return new ProviderPolicy(Duration.ofMillis(timeoutMs), maxRetries, Duration.ofMillis(retryBackoffMs),
                circuitFailureThreshold, Duration.ofSeconds(circuitOpenSeconds), rateLimitPerSecond,
                quotaPerWindow, Duration.ofSeconds(quotaWindowSeconds), cacheTtl,
                Duration.ofSeconds(negativeCacheTtlSeconds), cacheVersion);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAmapAlias() {
        return amapAlias;
    }

    public void setAmapAlias(String amapAlias) {
        this.amapAlias = amapAlias;
    }

    public String getQweatherAlias() {
        return qweatherAlias;
    }

    public void setQweatherAlias(String qweatherAlias) {
        this.qweatherAlias = qweatherAlias;
    }

    public String getLlmAlias() {
        return llmAlias;
    }

    public void setLlmAlias(String llmAlias) {
        this.llmAlias = llmAlias;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public int getCircuitOpenSeconds() {
        return circuitOpenSeconds;
    }

    public void setCircuitOpenSeconds(int circuitOpenSeconds) {
        this.circuitOpenSeconds = circuitOpenSeconds;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(int rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public int getQuotaPerWindow() {
        return quotaPerWindow;
    }

    public void setQuotaPerWindow(int quotaPerWindow) {
        this.quotaPerWindow = quotaPerWindow;
    }

    public int getQuotaWindowSeconds() {
        return quotaWindowSeconds;
    }

    public void setQuotaWindowSeconds(int quotaWindowSeconds) {
        this.quotaWindowSeconds = quotaWindowSeconds;
    }

    public int getRouteCacheTtlSeconds() {
        return routeCacheTtlSeconds;
    }

    public void setRouteCacheTtlSeconds(int routeCacheTtlSeconds) {
        this.routeCacheTtlSeconds = routeCacheTtlSeconds;
    }

    public int getWeatherCacheTtlSeconds() {
        return weatherCacheTtlSeconds;
    }

    public void setWeatherCacheTtlSeconds(int weatherCacheTtlSeconds) {
        this.weatherCacheTtlSeconds = weatherCacheTtlSeconds;
    }

    public int getPoiCacheTtlSeconds() {
        return poiCacheTtlSeconds;
    }

    public void setPoiCacheTtlSeconds(int poiCacheTtlSeconds) {
        this.poiCacheTtlSeconds = poiCacheTtlSeconds;
    }

    public int getNegativeCacheTtlSeconds() {
        return negativeCacheTtlSeconds;
    }

    public void setNegativeCacheTtlSeconds(int negativeCacheTtlSeconds) {
        this.negativeCacheTtlSeconds = negativeCacheTtlSeconds;
    }

    public String getCacheVersion() {
        return cacheVersion;
    }

    public void setCacheVersion(String cacheVersion) {
        this.cacheVersion = cacheVersion;
    }
}
