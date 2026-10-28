package com.agenttravel.backend.adapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProviderCallExecutor implements AutoCloseable {

    private final Clock clock;
    private final Map<ProviderKind, ProviderPolicy> policies;
    private final ProviderCallMetrics metrics;
    private final Map<ProviderKind, CircuitState> circuits = new EnumMap<>(ProviderKind.class);
    private final Map<ProviderKind, WindowState> rateWindows = new EnumMap<>(ProviderKind.class);
    private final Map<ProviderKind, WindowState> quotaWindows = new EnumMap<>(ProviderKind.class);
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "provider-adapter-call");
        thread.setDaemon(true);
        return thread;
    });

    public ProviderCallExecutor(Map<ProviderKind, ProviderPolicy> policies) {
        this(policies, Clock.systemUTC(), ProviderCallMetrics.noop());
    }

    public ProviderCallExecutor(Map<ProviderKind, ProviderPolicy> policies, Clock clock) {
        this(policies, clock, ProviderCallMetrics.noop());
    }

    public ProviderCallExecutor(
            Map<ProviderKind, ProviderPolicy> policies,
            Clock clock,
            ProviderCallMetrics metrics) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.policies = new EnumMap<>(ProviderKind.class);
        this.policies.putAll(policies);
        for (ProviderKind kind : ProviderKind.values()) {
            this.policies.putIfAbsent(kind, ProviderPolicy.defaults());
            circuits.put(kind, new CircuitState());
            rateWindows.put(kind, new WindowState());
            quotaWindows.put(kind, new WindowState());
        }
    }

    public <T> T call(ProviderKind provider, Callable<T> operation) {
        ProviderPolicy policy = policies.getOrDefault(provider, ProviderPolicy.defaults());
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            CircuitState circuit = circuits.get(provider);
            boolean circuitAdmitted = false;
            boolean operationStarted = false;
            try {
                boolean halfOpen = circuit.admit(provider, clock.instant());
                circuitAdmitted = true;
                if (halfOpen) {
                    metrics.circuitHalfOpened(provider);
                }
                admitRateAndQuota(provider, policy);
                operationStarted = true;
                T result = invoke(provider, operation, policy.timeout());
                circuit.success();
                return result;
            } catch (ProviderException exception) {
                if (circuitAdmitted) {
                    if (operationStarted) {
                        metrics.providerFailed(provider, exception.code());
                        circuit.failure(exception.code());
                        if (circuit.openIfNeeded(policy, clock.instant())) {
                            metrics.circuitOpened(provider);
                        }
                    } else {
                        circuit.cancelProbe();
                    }
                }
                if (!exception.retryable() || attempt == policy.maxRetries()) {
                    throw exception;
                }
                metrics.retry(provider);
                pause(provider, policy.retryBackoff());
            }
        }
        throw new ProviderException(provider, ProviderErrorCode.UNAVAILABLE, "供应商调用失败");
    }

    private <T> T invoke(ProviderKind provider, Callable<T> operation, Duration timeout) {
        Future<T> future = executor.submit(operation);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ProviderException(provider, ProviderErrorCode.TIMEOUT, "供应商调用超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, ProviderErrorCode.UNAVAILABLE, "供应商调用被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProviderException providerException) {
                throw providerException;
            }
            throw new ProviderException(provider, ProviderErrorCode.UNAVAILABLE, "供应商调用失败", cause);
        }
    }

    private void admitRateAndQuota(ProviderKind provider, ProviderPolicy policy) {
        Instant now = clock.instant();
        if (!rateWindows.get(provider).allow(now, Duration.ofSeconds(1), policy.rateLimitPerSecond())) {
            metrics.localRateLimited(provider);
            throw new ProviderException(provider, ProviderErrorCode.RATE_LIMITED, "供应商请求频率受限");
        }
        if (!quotaWindows.get(provider).allow(now, policy.quotaWindow(), policy.quotaPerWindow())) {
            metrics.quotaExceeded(provider);
            throw new ProviderException(provider, ProviderErrorCode.QUOTA_EXCEEDED, "供应商配额已用尽");
        }
    }

    private void pause(ProviderKind provider, Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, ProviderErrorCode.UNAVAILABLE, "重试被中断", exception);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class CircuitState {
        private int failures;
        private Instant openUntil;
        private boolean probeInFlight;

        synchronized boolean admit(ProviderKind provider, Instant now) {
            if (openUntil == null) {
                return false;
            }
            if (now.isBefore(openUntil) || probeInFlight) {
                throw new ProviderException(provider, ProviderErrorCode.CIRCUIT_OPEN, "供应商熔断中");
            }
            probeInFlight = true;
            return true;
        }

        synchronized void success() {
            failures = 0;
            openUntil = null;
            probeInFlight = false;
        }

        synchronized void failure(ProviderErrorCode code) {
            boolean probeFailure = probeInFlight;
            probeInFlight = false;
            if (probeFailure && !code.retryable()) {
                failures = 0;
                openUntil = null;
                return;
            }
            if (!code.retryable()) {
                return;
            }
            failures++;
        }

        synchronized void cancelProbe() {
            probeInFlight = false;
        }

        synchronized boolean openIfNeeded(ProviderPolicy policy, Instant now) {
            if (failures >= policy.circuitFailureThreshold()
                    && (openUntil == null || !now.isBefore(openUntil))) {
                openUntil = now.plus(policy.circuitOpenDuration());
                return true;
            }
            return false;
        }
    }

    private static final class WindowState {
        private final ArrayDeque<Instant> calls = new ArrayDeque<>();

        synchronized boolean allow(Instant now, Duration window, int limit) {
            Instant boundary = now.minus(window);
            while (!calls.isEmpty() && !calls.peekFirst().isAfter(boundary)) {
                calls.removeFirst();
            }
            if (calls.size() >= limit) {
                return false;
            }
            calls.addLast(now);
            return true;
        }
    }
}
