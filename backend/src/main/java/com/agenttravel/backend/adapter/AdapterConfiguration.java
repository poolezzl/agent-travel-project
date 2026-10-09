package com.agenttravel.backend.adapter;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

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
}
