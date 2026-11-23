package com.agenttravel.backend.traveltools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class TravelToolsScheduling {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler travelToolsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("travel-tools-");
        return scheduler;
    }
}
