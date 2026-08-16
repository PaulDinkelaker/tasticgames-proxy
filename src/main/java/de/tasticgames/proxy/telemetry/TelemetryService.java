package de.tasticgames.proxy.telemetry;

import de.tasticgames.client.dto.network.TelemetryBatchRequest;
import de.tasticgames.client.dto.network.TelemetryEventRequest;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async, bounded, batched telemetry publisher. Never blocks the caller; drops with a
 * counter when the queue is full; retries with exponential backoff and jitter.
 */
public final class TelemetryService implements ProxyService {

    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final ProxyMetrics metrics;
    private final ProxyIdentity identity;
    private final Logger logger;

    private volatile BlockingQueue<TelemetryEventRequest> queue;
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private volatile Instant nextAttempt = Instant.EPOCH;
    private volatile boolean running;

    public TelemetryService(ProxyConfigurationService configurationService, ProxyApiClient apiClient,
                            ProxyScheduler scheduler, ProxyMetrics metrics, ProxyIdentity identity, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "telemetry-service";
    }

    @Override
    public void start() {
        ProxyConfiguration.Telemetry configuration = configurationService.configuration().telemetry();
        queue = new ArrayBlockingQueue<>(configuration.queueCapacity());
        running = true;
        scheduler.repeat("telemetry-flush", configuration.flushInterval(), configuration.flushInterval(), this::flush);
    }

    @Override
    public void stop() {
        running = false;
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current == null || current.isEmpty() || !apiClient.enabled()) {
            return;
        }
        // bounded best-effort final flush (max 3 seconds)
        try {
            List<TelemetryEventRequest> batch = new ArrayList<>();
            current.drainTo(batch, configurationService.configuration().telemetry().batchSize());
            if (!batch.isEmpty()) {
                apiClient.call("telemetry.publish", client -> client.network().publishTelemetry(new TelemetryBatchRequest(batch)))
                        .get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.debug("Final telemetry flush skipped: {}", Throwables.rootMessage(e));
        }
    }

    /** Publishes an event; returns false when dropped (queue full / disabled). */
    public boolean publish(TelemetryEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running || !configurationService.configuration().telemetry().enabled() || !apiClient.enabled()) {
            return false;
        }
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current == null) {
            return false;
        }
        TelemetryEventRequest request = new TelemetryEventRequest(
                UUID.randomUUID(), event.type(), event.timestamp(), identity.proxyId(), identity.region(),
                identity.environment(), event.playerUuid(), event.sessionId(), event.serverId(),
                event.correlationId(), event.outcome(), event.durationMillis(), event.attributes());
        if (!current.offer(request)) {
            long count = dropped.incrementAndGet();
            metrics.increment("telemetry.dropped");
            if (count == 1 || count % 1000 == 0) {
                logger.warn("Telemetry queue full – dropped {} event(s) so far.", count);
            }
            return false;
        }
        metrics.gauge("telemetry.queue", current.size());
        return true;
    }

    public TelemetryEvent.Builder event(String type) {
        return TelemetryEvent.builder(type);
    }

    public int queueSize() {
        BlockingQueue<TelemetryEventRequest> current = queue;
        return current == null ? 0 : current.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long publishedCount() {
        return published.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    void flush() {
        BlockingQueue<TelemetryEventRequest> current = queue;
        if (current == null || current.isEmpty() || !apiClient.enabled()) {
            return;
        }
        if (Instant.now().isBefore(nextAttempt) || !flushing.compareAndSet(false, true)) {
            return;
        }
        ProxyConfiguration.Telemetry configuration = configurationService.configuration().telemetry();
        List<TelemetryEventRequest> batch = new ArrayList<>(configuration.batchSize());
        current.drainTo(batch, configuration.batchSize());
        if (batch.isEmpty()) {
            flushing.set(false);
            return;
        }
        CompletableFuture<?> future = apiClient.call("telemetry.publish",
                client -> client.network().publishTelemetry(new TelemetryBatchRequest(batch)));
        future.whenComplete((ignored, throwable) -> {
            try {
                if (throwable != null) {
                    int failures = consecutiveFailures.incrementAndGet();
                    Duration backoff = backoff(failures, configuration.maxBackoff());
                    nextAttempt = Instant.now().plus(backoff);
                    metrics.increment("telemetry.batch_failures");
                    // put the batch back (front is not possible with a queue; re-offer, drop overflow)
                    for (TelemetryEventRequest event : batch) {
                        if (!current.offer(event)) {
                            dropped.incrementAndGet();
                            metrics.increment("telemetry.dropped");
                        }
                    }
                    if (failures == 1 || failures % 10 == 0) {
                        logger.warn("Telemetry publish failed ({}x): {} – retry in {}s", failures,
                                Throwables.rootMessage(throwable), backoff.toSeconds());
                    }
                } else {
                    consecutiveFailures.set(0);
                    published.addAndGet(batch.size());
                    metrics.add("telemetry.published", batch.size());
                }
                metrics.gauge("telemetry.queue", current.size());
            } finally {
                flushing.set(false);
            }
        });
    }

    public static Duration backoff(int failures, Duration max) {
        long seconds = Math.min(max.toSeconds(), 1L << Math.min(failures, 16));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, seconds / 4 + 1));
        return Duration.ofSeconds(Math.min(max.toSeconds(), seconds + jitter));
    }
}
