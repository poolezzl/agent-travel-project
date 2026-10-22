package com.agenttravel.backend.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdapterProperties.class)
public class AdapterConfiguration {

    @Bean
    Clock providerClock() {
        return Clock.systemUTC();
    }

    @Bean
    ProviderCallExecutor providerCallExecutor(AdapterProperties properties, Clock providerClock) {
        Map<ProviderKind, ProviderPolicy> policies = new EnumMap<>(ProviderKind.class);
        ProviderPolicy policy = properties.policy();
        for (ProviderKind kind : ProviderKind.values()) {
            policies.put(kind, policy);
        }
        return new ProviderCallExecutor(policies, providerClock);
    }

    @Bean(name = "routeProviderCache")
    InMemoryProviderCache<RouteData> routeProviderCache() {
        return new InMemoryProviderCache<>();
    }

    @Bean(name = "poiProviderCache")
    InMemoryProviderCache<java.util.List<PoiRecord>> poiProviderCache() {
        return new InMemoryProviderCache<>();
    }

    @Bean(name = "weatherProviderCache")
    InMemoryProviderCache<java.util.List<WeatherRecord>> weatherProviderCache() {
        return new InMemoryProviderCache<>();
    }

    @Bean
    @ConditionalOnMissingBean(ProviderHttpTransport.class)
    ProviderHttpTransport providerHttpTransport(AdapterProperties properties) {
        return new JavaProviderHttpTransport(
                java.time.Duration.ofMillis(properties.getConnectTimeoutMs()),
                properties.getHttpMaxResponseBytes());
    }

    @Bean
    @ConditionalOnProperty(name = "app.adapters.mode", havingValue = "mock", matchIfMissing = true)
    MockAmapAdapter mockAmapAdapter(AdapterProperties properties, Clock providerClock,
            ProviderCallExecutor providerCallExecutor,
            InMemoryProviderCache<RouteData> routeProviderCache,
            InMemoryProviderCache<java.util.List<PoiRecord>> poiProviderCache) {
        return new MockAmapAdapter(properties.getAmapAlias(), properties.policy(
                java.time.Duration.ofSeconds(properties.getRouteCacheTtlSeconds())), properties.policy(
                java.time.Duration.ofSeconds(properties.getPoiCacheTtlSeconds())), providerClock,
                providerCallExecutor, routeProviderCache, poiProviderCache);
    }

    @Bean
    @ConditionalOnProperty(name = "app.adapters.mode", havingValue = "mock", matchIfMissing = true)
    MockQWeatherAdapter mockQWeatherAdapter(AdapterProperties properties, Clock providerClock,
            ProviderCallExecutor providerCallExecutor,
            InMemoryProviderCache<java.util.List<WeatherRecord>> weatherProviderCache) {
        return new MockQWeatherAdapter(properties.getQweatherAlias(), properties.policy(
                java.time.Duration.ofSeconds(properties.getWeatherCacheTtlSeconds())), 3, providerClock,
                providerCallExecutor, weatherProviderCache);
    }

    @Bean
    @ConditionalOnProperty(name = "app.adapters.mode", havingValue = "mock", matchIfMissing = true)
    MockModelAdapter mockModelAdapter(AdapterProperties properties, Clock providerClock,
            ProviderCallExecutor providerCallExecutor) {
        return new MockModelAdapter(properties.getLlmAlias(), providerCallExecutor, providerClock);
    }

    @Bean
    @ConditionalOnProperty(name = "app.adapters.mode", havingValue = "real")
    RealAmapAdapter realAmapAdapter(
            AdapterProperties properties,
            Clock providerClock,
            ProviderCallExecutor providerCallExecutor,
            ProviderHttpTransport providerHttpTransport,
            ObjectMapper objectMapper,
            InMemoryProviderCache<RouteData> routeProviderCache,
            InMemoryProviderCache<java.util.List<PoiRecord>> poiProviderCache) {
        return new RealAmapAdapter(
                properties.getAmapAlias(),
                secureBaseUri(properties.getAmapBaseUrl(), "高德服务地址"),
                properties.getAmapWebKey(),
                properties.policy(java.time.Duration.ofSeconds(properties.getRouteCacheTtlSeconds())),
                properties.policy(java.time.Duration.ofSeconds(properties.getPoiCacheTtlSeconds())),
                providerClock,
                providerCallExecutor,
                providerHttpTransport,
                objectMapper,
                routeProviderCache,
                poiProviderCache);
    }

    @Bean
    @ConditionalOnProperty(name = "app.adapters.mode", havingValue = "real")
    RealQWeatherAdapter realQWeatherAdapter(
            AdapterProperties properties,
            Clock providerClock,
            ProviderCallExecutor providerCallExecutor,
            ProviderHttpTransport providerHttpTransport,
            ObjectMapper objectMapper,
            InMemoryProviderCache<java.util.List<WeatherRecord>> weatherProviderCache) {
        return new RealQWeatherAdapter(
                properties.getQweatherAlias(),
                secureBaseUri(properties.getQweatherApiHost(), "和风天气服务地址"),
                properties.getQweatherApiKey(),
                properties.getQweatherForecastDays(),
                properties.getQweatherZoneId(),
                objectMapper,
                properties.policy(java.time.Duration.ofSeconds(properties.getWeatherCacheTtlSeconds())),
                providerClock,
                providerCallExecutor,
                providerHttpTransport,
                weatherProviderCache);
    }

    private URI secureBaseUri(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.contains("://") ? value : "https://" + value;
        URI uri = URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(field + "必须是无查询参数的 HTTPS 地址");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(field + "不能包含查询参数或片段");
        }
        return uri;
    }
}
