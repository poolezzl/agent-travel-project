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
    private final Map<ProviderKind, CircuitState> circuits = new EnumMap<>(ProviderKind.class);
    private final Map<ProviderKind, WindowState> rateWindows = new EnumMap<>(ProviderKind.class);
    private final Map<ProviderKind, WindowState> quotaWindows = new EnumMap<>(ProviderKind.class);
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "provider-adapter-call");
        thread.setDaemon(true);
        return thread;
    });

    public ProviderCallExecutor(Map<ProviderKind, ProviderPolicy> policies) {
        this(policies, Clock.systemUTC());
    }

    public ProviderCallExecutor(Map<ProviderKind, ProviderPolicy> policies, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
            admit(provider, policy);
            try {
                T result = invoke(provider, operation, policy.timeout());
                circuits.get(provider).success();
                return result;
            } catch (ProviderException exception) {
                circuits.get(provider).failure(exception.code());
                circuits.get(provider).openIfNeeded(policy, clock.instant());
                if (!exception.retryable() || attempt == policy.maxRetries()) {
                    throw exception;
                }
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

    private void admit(ProviderKind provider, ProviderPolicy policy) {
        Instant now = clock.instant();
        circuits.get(provider).admit(provider, now, policy.circuitOpenDuration());
        if (!rateWindows.get(provider).allow(now, Duration.ofSeconds(1), policy.rateLimitPerSecond())) {
            throw new ProviderException(provider, ProviderErrorCode.RATE_LIMITED, "供应商请求频率受限");
        }
        if (!quotaWindows.get(provider).allow(now, policy.quotaWindow(), policy.quotaPerWindow())) {
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

        synchronized void admit(ProviderKind provider, Instant now, Duration openDuration) {
            if (openUntil == null) {
                return;
            }
            if (now.isBefore(openUntil) || probeInFlight) {
                throw new ProviderException(provider, ProviderErrorCode.CIRCUIT_OPEN, "供应商熔断中");
            }
            probeInFlight = true;
        }

        synchronized void success() {
            failures = 0;
            openUntil = null;
            probeInFlight = false;
        }

        synchronized void failure(ProviderErrorCode code) {
            if (!code.retryable()) {
                return;
            }
            failures++;
            probeInFlight = false;
        }

        synchronized void openIfNeeded(ProviderPolicy policy, Instant now) {
            if (failures >= policy.circuitFailureThreshold()) {
                openUntil = now.plus(policy.circuitOpenDuration());
            }
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
