package com.agenttravel.backend.task;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.s11")
public class TaskProperties {

    private boolean enabled = true;
    private int operationTimeoutSeconds = 180;
    private int confirmationTimeoutHours = 24;
    private int leaseSeconds = 30;
    private int heartbeatSeconds = 10;
    private int maxStepAttempts = 3;
    private int outboxBatchSize = 20;
    private int outboxMaxAttempts = 5;
    private long relayIntervalMs = 500;
    private boolean relayEnabled = true;
    private boolean consumerEnabled = true;
    private boolean heartbeatSchedulerEnabled = true;
    private boolean sseSchedulerEnabled = true;
    private long replayIntervalSeconds = 30;
    private long replayAfterSeconds = 10;
    private long consumerIntervalMs = 250;
    private int consumerBatchSize = 10;
    private long reconnectSourceFreshnessSeconds = 3600;
    private long sseTimeoutMs = 120000;
    private long ssePollIntervalMs = 500;
    private int sseBatchSize = 100;
    private int sseMaxSubscribers = 100;
    private String workerGroup = "s11-workers-v1";
    private String workerName = "backend-worker";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOperationTimeoutSeconds() {
        return operationTimeoutSeconds;
    }

    public void setOperationTimeoutSeconds(int operationTimeoutSeconds) {
        this.operationTimeoutSeconds = operationTimeoutSeconds;
    }

    public int getConfirmationTimeoutHours() {
        return confirmationTimeoutHours;
    }

    public void setConfirmationTimeoutHours(int confirmationTimeoutHours) {
        this.confirmationTimeoutHours = confirmationTimeoutHours;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getMaxStepAttempts() {
        return maxStepAttempts;
    }

    public void setMaxStepAttempts(int maxStepAttempts) {
        this.maxStepAttempts = maxStepAttempts;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public int getOutboxMaxAttempts() {
        return outboxMaxAttempts;
    }

    public void setOutboxMaxAttempts(int outboxMaxAttempts) {
        this.outboxMaxAttempts = outboxMaxAttempts;
    }

    public long getRelayIntervalMs() {
        return relayIntervalMs;
    }

    public void setRelayIntervalMs(long relayIntervalMs) {
        this.relayIntervalMs = relayIntervalMs;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    public void setConsumerEnabled(boolean consumerEnabled) {
        this.consumerEnabled = consumerEnabled;
    }

    public boolean isHeartbeatSchedulerEnabled() {
        return heartbeatSchedulerEnabled;
    }

    public void setHeartbeatSchedulerEnabled(boolean heartbeatSchedulerEnabled) {
        this.heartbeatSchedulerEnabled = heartbeatSchedulerEnabled;
    }

    public boolean isSseSchedulerEnabled() {
        return sseSchedulerEnabled;
    }

    public void setSseSchedulerEnabled(boolean sseSchedulerEnabled) {
        this.sseSchedulerEnabled = sseSchedulerEnabled;
    }

    public long getReplayIntervalSeconds() {
        return replayIntervalSeconds;
    }

    public void setReplayIntervalSeconds(long replayIntervalSeconds) {
        this.replayIntervalSeconds = replayIntervalSeconds;
    }

    public long getReplayAfterSeconds() {
        return replayAfterSeconds;
    }

    public void setReplayAfterSeconds(long replayAfterSeconds) {
        this.replayAfterSeconds = replayAfterSeconds;
    }

    public long getConsumerIntervalMs() {
        return consumerIntervalMs;
    }

    public void setConsumerIntervalMs(long consumerIntervalMs) {
        this.consumerIntervalMs = consumerIntervalMs;
    }

    public int getConsumerBatchSize() {
        return consumerBatchSize;
    }

    public void setConsumerBatchSize(int consumerBatchSize) {
        this.consumerBatchSize = consumerBatchSize;
    }

    public long getReconnectSourceFreshnessSeconds() {
        return reconnectSourceFreshnessSeconds;
    }

    public void setReconnectSourceFreshnessSeconds(long reconnectSourceFreshnessSeconds) {
        this.reconnectSourceFreshnessSeconds = reconnectSourceFreshnessSeconds;
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    public long getSsePollIntervalMs() {
        return ssePollIntervalMs;
    }

    public void setSsePollIntervalMs(long ssePollIntervalMs) {
        this.ssePollIntervalMs = ssePollIntervalMs;
    }

    public int getSseBatchSize() {
        return sseBatchSize;
    }

    public void setSseBatchSize(int sseBatchSize) {
        this.sseBatchSize = sseBatchSize;
    }

    public int getSseMaxSubscribers() {
        return sseMaxSubscribers;
    }

    public void setSseMaxSubscribers(int sseMaxSubscribers) {
        this.sseMaxSubscribers = sseMaxSubscribers;
    }

    public String getWorkerGroup() {
        return workerGroup;
    }

    public void setWorkerGroup(String workerGroup) {
        this.workerGroup = workerGroup;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
