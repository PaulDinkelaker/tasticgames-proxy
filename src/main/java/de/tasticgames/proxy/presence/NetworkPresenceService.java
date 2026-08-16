package de.tasticgames.proxy.presence;

import de.tasticgames.client.dto.network.BulkPresenceRequest;
import de.tasticgames.client.dto.network.NetworkPresenceResponse;
import de.tasticgames.client.dto.network.PresenceConnectRequest;
import de.tasticgames.client.dto.network.PresenceDisconnectRequest;
import de.tasticgames.client.dto.network.PresenceSwitchRequest;
import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.player.NetworkPlayerManager;
import de.tasticgames.proxy.player.NetworkPlayerSession;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Presence 1.0: central, session-aware presence via the API. Operations of one player are
 * serialized (connect before switch before disconnect); failures are logged and retried
 * implicitly by the periodic heartbeat. Never blocks the event thread.
 */
public final class NetworkPresenceService implements ProxyService {

    private final ProxyApiClient apiClient;
    private final ProxyConfigurationService configurationService;
    private final NetworkPlayerManager playerManager;
    private final ServerRegistryService registry;
    private final ProxyScheduler scheduler;
    private final ProxyMetrics metrics;
    private final ProxyIdentity identity;
    private final Logger logger;

    private final ConcurrentMap<UUID, CompletableFuture<Void>> operations = new ConcurrentHashMap<>();
    private volatile boolean cleanupLeader;

    public NetworkPresenceService(ProxyApiClient apiClient, ProxyConfigurationService configurationService,
                                  NetworkPlayerManager playerManager, ServerRegistryService registry,
                                  ProxyScheduler scheduler, ProxyMetrics metrics, ProxyIdentity identity, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.playerManager = Objects.requireNonNull(playerManager, "playerManager");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "network-presence-service";
    }

    @Override
    public void start() {
        ProxyConfiguration.Presence config = configurationService.configuration().presence();
        scheduler.repeat("presence-heartbeat", config.heartbeatInterval(), config.heartbeatInterval(), this::heartbeatAll);
        scheduler.repeat("presence-cleanup", config.cleanupInterval(), config.cleanupInterval(), this::cleanupStale);
    }

    @Override
    public void stop() {
        // bounded wait for in-flight presence operations (max 3s) – disconnects are enqueued by the bootstrap
        try {
            CompletableFuture.allOf(operations.values().toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        } finally {
            operations.clear();
        }
    }

    public CompletableFuture<Void> connected(NetworkPlayerSession session, String serverName, boolean firstServer) {
        Objects.requireNonNull(session, "session");
        String server = Objects.requireNonNull(serverName, "serverName");
        ServerTypeResponse type = typeOf(server);
        UUID uuid = session.minecraftUuid();
        session.presenceHeartbeatSent(Instant.now());
        if (firstServer) {
            return enqueue(uuid, "presence.connect", () -> apiClient.call("presence.connect", client ->
                    client.network().connect(uuid, new PresenceConnectRequest(session.sessionId(), identity.proxyId(),
                            identity.region(), server, type))));
        }
        return enqueue(uuid, "presence.switch", () -> apiClient.call("presence.switch", client ->
                client.network().switchServer(uuid, new PresenceSwitchRequest(session.sessionId(), server, type))));
    }

    public CompletableFuture<Void> disconnected(NetworkPlayerSession session) {
        Objects.requireNonNull(session, "session");
        UUID uuid = session.minecraftUuid();
        return enqueue(uuid, "presence.disconnect", () -> apiClient.call("presence.disconnect", client ->
                client.network().disconnect(uuid, new PresenceDisconnectRequest(session.sessionId(), identity.proxyId()))));
    }

    public CompletableFuture<Optional<NetworkPresenceResponse>> lookup(UUID uuid) {
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return apiClient.call("presence.lookup", client -> client.network().findPresence(uuid));
    }

    public CompletableFuture<List<NetworkPresenceResponse>> lookupAll(Collection<UUID> uuids) {
        if (!apiClient.enabled() || uuids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<UUID> list = new ArrayList<>(uuids);
        List<CompletableFuture<List<NetworkPresenceResponse>>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += 200) {
            List<UUID> chunk = list.subList(i, Math.min(list.size(), i + 200));
            chunks.add(apiClient.call("presence.bulk", client -> client.network().bulkPresence(new BulkPresenceRequest(chunk))));
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<NetworkPresenceResponse> all = new ArrayList<>();
            for (CompletableFuture<List<NetworkPresenceResponse>> chunk : chunks) {
                all.addAll(chunk.join());
            }
            return all;
        });
    }

    /** Sends a heartbeat for every local player (keeps central sessions from expiring). */
    void heartbeatAll() {
        if (!apiClient.enabled()) {
            return;
        }
        Instant now = Instant.now();
        for (NetworkPlayerSession session : playerManager.onlinePlayers()) {
            if (!session.connectedToServer()) {
                continue;
            }
            session.presenceHeartbeatSent(now);
            UUID uuid = session.minecraftUuid();
            enqueue(uuid, "presence.heartbeat", () -> apiClient.call("presence.heartbeat",
                    client -> client.network().heartbeat(uuid, session.sessionId())));
        }
        metrics.gauge("presence.local_players", playerManager.onlineCount());
    }

    /** Asks the API to close stale sessions/proxies (any proxy may do this; the API is idempotent). */
    void cleanupStale() {
        if (!apiClient.enabled()) {
            return;
        }
        int staleAfter = (int) configurationService.configuration().presence().staleAfter().toSeconds();
        apiClient.call("presence.cleanup", client -> client.network().cleanupStalePresence(staleAfter))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Presence cleanup failed: {}", Throwables.rootMessage(throwable));
                        return;
                    }
                    if (result.sessionsClosed() > 0 || result.proxiesMarkedOffline() > 0) {
                        logger.info("Presence cleanup closed {} stale session(s), {} stale proxy(ies).",
                                result.sessionsClosed(), result.proxiesMarkedOffline());
                    }
                });
    }

    private ServerTypeResponse typeOf(String server) {
        return registry.find(server).map(NetworkServer::type)
                .map(t -> ServerTypeResponse.valueOf(t.name())).orElse(ServerTypeResponse.OTHER);
    }

    private CompletableFuture<Void> enqueue(UUID uuid, String name, Supplier<CompletableFuture<?>> supplier) {
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> operation = operations.compute(uuid, (ignored, previous) -> {
            CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((r, t) -> null);
            return predecessor.thenCompose(ignoredResult -> supplier.get().thenApply(r -> null));
        });
        operation.whenComplete((ignored, throwable) -> {
            operations.remove(uuid, operation);
            if (throwable != null) {
                metrics.increment("presence.failures");
                logger.warn("Presence operation {} for {} failed: {}", name, uuid, Throwables.rootMessage(throwable));
            } else {
                metrics.increment("presence.successes");
            }
        });
        return operation;
    }

    Duration heartbeatInterval() {
        return configurationService.configuration().presence().heartbeatInterval();
    }
}
