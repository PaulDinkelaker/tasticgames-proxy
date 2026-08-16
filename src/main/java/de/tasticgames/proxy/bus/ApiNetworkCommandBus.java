package de.tasticgames.proxy.bus;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.network.NetworkCommandAckRequest;
import de.tasticgames.client.dto.network.NetworkCommandRequest;
import de.tasticgames.client.dto.network.NetworkCommandResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API/MySQL backed command bus: durable submissions, batch polling, idempotent processing
 * (recently processed command ids are remembered so a redelivery after a lost ack is a no-op),
 * bounded per-poll batches and asynchronous acknowledgements. Commands for players connected
 * to this proxy are handled locally without a round trip.
 */
public final class ApiNetworkCommandBus implements NetworkCommandBus {

    private static final int REMEMBERED_IDS = 5000;

    private final ProxyServer proxyServer;
    private final ProxyApiClient apiClient;
    private final ProxyConfigurationService configurationService;
    private final ProxyScheduler scheduler;
    private final ProxyMetrics metrics;
    private final ProxyIdentity identity;
    private final Logger logger;

    private final Map<String, NetworkCommandHandler> handlers = new ConcurrentHashMap<>();
    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();
    private final Deque<UUID> processedOrder = new ArrayDeque<>();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private volatile int lastBatchSize;
    private volatile Instant lastPollAt;

    public ApiNetworkCommandBus(ProxyServer proxyServer, ProxyApiClient apiClient, ProxyConfigurationService configurationService,
                                ProxyScheduler scheduler, ProxyMetrics metrics, ProxyIdentity identity, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "network-command-bus";
    }

    @Override
    public void start() {
        ProxyConfiguration.Bus config = configurationService.configuration().bus();
        scheduler.repeat("command-bus-poll", config.pollInterval(), config.pollInterval(), this::poll);
        logger.info("Network command bus started (API polling every {} ms).", config.pollInterval().toMillis());
    }

    @Override
    public void stop() {
        handlers.clear();
        processed.clear();
        synchronized (processedOrder) {
            processedOrder.clear();
        }
    }

    @Override
    public void subscribe(String type, NetworkCommandHandler handler) {
        handlers.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public boolean available() {
        return apiClient.enabled();
    }

    @Override
    public int pendingLocalCommands() {
        return lastBatchSize;
    }

    @Override
    public long processedCount() {
        return processedCount.get();
    }

    @Override
    public long failedCount() {
        return failedCount.get();
    }

    public Instant lastPollAt() {
        return lastPollAt;
    }

    // ------------------------------------------------------------------ submissions

    @Override
    public CompletableFuture<Submission> broadcast(String type, Map<String, String> payload) {
        UUID commandId = UUID.randomUUID();
        // handle locally right away; remember the id so our own polled copy is skipped
        remember(commandId);
        handleLocally(new NetworkCommand(commandId, type, identity.proxyId(), null, payload, Instant.now(), expiry()));
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(new Submission(commandId, false, List.of(identity.proxyId()), false, true));
        }
        return submit(new NetworkCommandRequest(commandId, type, identity.proxyId(), null, null, PayloadCodec.encode(payload), expiry()), true);
    }

    @Override
    public CompletableFuture<Submission> sendToProxy(String proxyId, String type, Map<String, String> payload) {
        UUID commandId = UUID.randomUUID();
        if (proxyId.equalsIgnoreCase(identity.proxyId())) {
            remember(commandId);
            handleLocally(new NetworkCommand(commandId, type, identity.proxyId(), null, payload, Instant.now(), expiry()));
            return CompletableFuture.completedFuture(new Submission(commandId, false, List.of(identity.proxyId()), false, true));
        }
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(new Submission(commandId, false, List.of(), true, false));
        }
        return submit(new NetworkCommandRequest(commandId, type, identity.proxyId(), proxyId, null, PayloadCodec.encode(payload), expiry()), false);
    }

    @Override
    public CompletableFuture<Submission> sendToPlayer(UUID playerUuid, String type, Map<String, String> payload) {
        UUID commandId = UUID.randomUUID();
        if (proxyServer.getPlayer(playerUuid).isPresent()) {
            remember(commandId);
            handleLocally(new NetworkCommand(commandId, type, identity.proxyId(), playerUuid, payload, Instant.now(), expiry()));
            return CompletableFuture.completedFuture(new Submission(commandId, false, List.of(identity.proxyId()), false, true));
        }
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(new Submission(commandId, false, List.of(), true, false));
        }
        return submit(new NetworkCommandRequest(commandId, type, identity.proxyId(), null, playerUuid, PayloadCodec.encode(payload), expiry()), false);
    }

    private CompletableFuture<Submission> submit(NetworkCommandRequest request, boolean handledLocally) {
        return apiClient.call("bus.submit", client -> client.network().submitCommand(request))
                .thenApply(response -> {
                    metrics.increment("bus.submitted");
                    return new Submission(response.commandId(), response.duplicate(), response.deliveredToProxyIds(),
                            response.undeliverable(), handledLocally);
                });
    }

    private Instant expiry() {
        return Instant.now().plus(configurationService.configuration().bus().commandTtl());
    }

    // ------------------------------------------------------------------ polling

    void poll() {
        if (!apiClient.enabled() || !polling.compareAndSet(false, true)) {
            return;
        }
        int batch = configurationService.configuration().bus().pollBatchSize();
        apiClient.call("bus.poll", client -> client.network().pollCommands(identity.proxyId(), batch))
                .whenComplete((deliveries, throwable) -> {
                    try {
                        if (throwable != null) {
                            logger.debug("Command bus poll failed: {}", Throwables.rootMessage(throwable));
                            return;
                        }
                        lastPollAt = Instant.now();
                        lastBatchSize = deliveries.size();
                        for (NetworkCommandResponse delivery : deliveries) {
                            process(delivery);
                        }
                    } finally {
                        polling.set(false);
                    }
                });
    }

    private void process(NetworkCommandResponse delivery) {
        if (!remember(delivery.commandId())) {
            acknowledge(delivery.id(), true, "duplicate");
            return;
        }
        Map<String, String> payload;
        try {
            payload = PayloadCodec.decode(delivery.payload());
        } catch (IllegalArgumentException e) {
            failedCount.incrementAndGet();
            acknowledge(delivery.id(), false, "invalid payload");
            return;
        }
        NetworkCommand command = new NetworkCommand(delivery.commandId(), delivery.type(), delivery.sourceProxyId(),
                delivery.targetPlayerUuid(), payload, delivery.createdAt(), delivery.expiresAt());
        dispatch(command).whenComplete((result, throwable) -> {
            if (throwable != null) {
                failedCount.incrementAndGet();
                metrics.increment("bus.failed");
                logger.warn("Network command {} ({}) failed: {}", command.commandId(), command.type(), Throwables.rootMessage(throwable));
                acknowledge(delivery.id(), false, Throwables.rootMessage(throwable));
            } else {
                processedCount.incrementAndGet();
                metrics.increment("bus.processed");
                acknowledge(delivery.id(), true, result);
            }
        });
    }

    private void handleLocally(NetworkCommand command) {
        dispatch(command).whenComplete((result, throwable) -> {
            if (throwable != null) {
                failedCount.incrementAndGet();
                logger.warn("Local network command {} ({}) failed: {}", command.commandId(), command.type(), Throwables.rootMessage(throwable));
            } else {
                processedCount.incrementAndGet();
            }
        });
    }

    private CompletableFuture<String> dispatch(NetworkCommand command) {
        NetworkCommandHandler handler = handlers.get(command.type());
        if (handler == null) {
            return CompletableFuture.completedFuture("no handler for " + command.type());
        }
        try {
            return handler.handle(command);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void acknowledge(long deliveryId, boolean success, String result) {
        String truncated = result == null ? "" : result.length() > 250 ? result.substring(0, 250) : result;
        apiClient.call("bus.ack", client -> client.network().acknowledgeCommand(deliveryId, new NetworkCommandAckRequest(identity.proxyId(), success, truncated)))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Ack of command delivery {} failed: {}", deliveryId, Throwables.rootMessage(throwable));
                    }
                });
    }

    /** @return true when the id was not seen before */
    private boolean remember(UUID commandId) {
        if (!processed.add(commandId)) {
            return false;
        }
        synchronized (processedOrder) {
            processedOrder.addLast(commandId);
            while (processedOrder.size() > REMEMBERED_IDS) {
                processed.remove(processedOrder.pollFirst());
            }
        }
        return true;
    }

    Duration pollInterval() {
        return configurationService.configuration().bus().pollInterval();
    }
}
