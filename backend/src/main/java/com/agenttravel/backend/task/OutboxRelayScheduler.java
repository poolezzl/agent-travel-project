package com.agenttravel.backend.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService relay;
    private final TaskProperties properties;

    public OutboxRelayScheduler(OutboxRelayService relay, TaskProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.s11.relay-interval-ms:500}")
    public void relayPending() {
        if (properties.isRelayEnabled()) {
            relay.relayPending();
            relay.replayPublishedForRestartedQueue();
        }
    }
}
