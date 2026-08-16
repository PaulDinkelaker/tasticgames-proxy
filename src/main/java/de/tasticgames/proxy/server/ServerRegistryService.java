package de.tasticgames.proxy.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tasticgames.client.dto.network.NetworkServerResponse;
import de.tasticgames.client.dto.network.ServerAdminStateResponse;
import de.tasticgames.client.dto.network.ServerRegistrationRequest;
import de.tasticgames.client.dto.network.ServerStateChangeRequest;
import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.Ids;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Network server registry v2. Velocity is the source of the server list (name/address),
 * the configuration adds static metadata, and the API is the source of truth for the
 * administrative state (pulled periodically and on bus notifications; pushed on changes).
 * The local copy acts as last-known-good cache when the API is unavailable.
 */
public final class ServerRegistryService implements ProxyService {

    private static final Duration SYNC_INTERVAL = Duration.ofSeconds(20);

    private final ProxyServer proxyServer;
    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final ProxyIdentity identity;
    private final Logger logger;

    private final ConcurrentMap<String, NetworkServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<NetworkServer>> stateListeners = new ConcurrentHashMap<>();
    private volatile Instant lastSyncAt;
    private volatile boolean centralStateLoaded;

    public ServerRegistryService(ProxyServer proxyServer, ProxyConfigurationService configurationService,
                                 ProxyApiClient apiClient, ProxyScheduler scheduler, TelemetryService telemetry,
                                 ProxyIdentity identity, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "server-registry-service";
    }

    @Override
    public void start() {
        servers.clear();
        for (RegisteredServer registeredServer : proxyServer.getAllServers()) {
            register(registeredServer);
        }
        logger.info("Server registry initialized with {} server(s): {}", servers.size(), servers.keySet());
        scheduler.repeat("server-registry-sync", Duration.ofSeconds(2), SYNC_INTERVAL, this::synchronize);
    }

    @Override
    public void stop() {
        servers.clear();
        stateListeners.clear();
    }

    // ------------------------------------------------------------------ registration

    public NetworkServer register(RegisteredServer registeredServer) {
        Objects.requireNonNull(registeredServer, "registeredServer");
        String serverId = Ids.serverId(registeredServer.getServerInfo().getName());
        NetworkServer existing = servers.get(serverId);
        if (existing != null) {
            return existing;
        }
        ProxyConfiguration.ServerDefinition definition = configurationService.configuration().servers().get(serverId);
        NetworkServer server;
        if (definition != null) {
            server = new NetworkServer(registeredServer,
                    ServerType.find(definition.type()).orElse(ServerType.OTHER), definition.region(),
                    definition.capacity(), definition.weight(), definition.tags());
        } else {
            server = new NetworkServer(registeredServer, ServerType.infer(serverId), identity.region(), 100, 100, List.of());
            logger.warn("Server {} has no entry in config.properties (servers.{}.*) – using inferred type {} and defaults.",
                    serverId, serverId, server.type());
        }
        NetworkServer previous = servers.putIfAbsent(serverId, server);
        if (previous != null) {
            return previous;
        }
        logger.info("Registered network server {} (type={}, region={}, capacity={}).", serverId, server.type(),
                server.region(), server.capacity());
        pushRegistration(server);
        return server;
    }

    public Optional<NetworkServer> unregister(String serverName) {
        NetworkServer removed = servers.remove(Ids.serverId(serverName));
        if (removed != null) {
            logger.info("Unregistered network server {}.", removed.serverId());
        }
        return Optional.ofNullable(removed);
    }

    /** Applies (re)loaded static metadata from configuration. */
    public void applyConfiguration() {
        for (NetworkServer server : servers.values()) {
            ProxyConfiguration.ServerDefinition definition = configurationService.configuration().servers().get(server.serverId());
            if (definition != null) {
                server.updateMetadata(ServerType.find(definition.type()).orElse(ServerType.OTHER), definition.region(),
                        definition.capacity(), definition.weight(), definition.tags());
                pushRegistration(server);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public Optional<NetworkServer> find(String serverName) {
        if (!Ids.isServerId(serverName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(servers.get(Ids.serverId(serverName)));
    }

    public NetworkServer require(String serverName) {
        return find(serverName).orElseThrow(() -> new IllegalStateException("Network server is not registered: " + serverName));
    }

    public Collection<NetworkServer> servers() {
        return List.copyOf(servers.values());
    }

    public List<NetworkServer> serversOfType(ServerType type) {
        return servers.values().stream().filter(s -> s.type() == type).toList();
    }

    public Collection<NetworkServer> acceptingServers() {
        return servers.values().stream().filter(NetworkServer::acceptingPlayers).toList();
    }

    public int size() {
        return servers.size();
    }

    public Instant lastSyncAt() {
        return lastSyncAt;
    }

    public boolean centralStateLoaded() {
        return centralStateLoaded;
    }

    public void onStateChanged(String listenerId, Consumer<NetworkServer> listener) {
        stateListeners.put(listenerId, listener);
    }

    // ------------------------------------------------------------------ admin state

    /**
     * Changes the administrative state centrally (API) and locally. When the API is not
     * available the change is applied locally and re-pushed by the next sync.
     */
    public CompletableFuture<NetworkServer> setState(String serverName, ServerAdminState state, String changedBy, String reason) {
        NetworkServer server = require(serverName);
        Objects.requireNonNull(state, "state");
        if (!apiClient.enabled()) {
            applyLocalState(server, state, changedBy, reason, Instant.now(), server.stateVersion());
            return CompletableFuture.completedFuture(server);
        }
        return apiClient.call("server.state", client -> client.network().changeServerState(server.serverId(),
                        new ServerStateChangeRequest(ServerAdminStateResponse.valueOf(state.name()), changedBy, reason, null)))
                .thenApply(response -> {
                    applyRemote(server, response);
                    return server;
                })
                .exceptionallyCompose(throwable -> {
                    logger.warn("Central state change for {} failed ({}); applying locally, will re-sync.",
                            server.serverId(), Throwables.rootMessage(throwable));
                    applyLocalState(server, state, changedBy, reason, Instant.now(), server.stateVersion());
                    return CompletableFuture.completedFuture(server);
                });
    }

    private void applyLocalState(NetworkServer server, ServerAdminState state, String changedBy, String reason,
                                 Instant at, long version) {
        ServerAdminState previous = server.adminState();
        if (server.applyAdminState(state, changedBy, reason, at, version)) {
            logger.info("Server {} state {} -> {} (by {}{}).", server.serverId(), previous, state, changedBy,
                    reason == null || reason.isBlank() ? "" : ", " + reason);
            telemetry.publish(telemetry.event(TelemetryTypes.SERVER_STATE_CHANGED)
                    .server(server.serverId()).outcome(state)
                    .attribute("previous", previous).attribute("changedBy", changedBy).build());
            stateListeners.values().forEach(listener -> {
                try {
                    listener.accept(server);
                } catch (RuntimeException e) {
                    logger.warn("Server state listener failed: {}", Throwables.rootMessage(e));
                }
            });
        }
    }

    // ------------------------------------------------------------------ central sync

    /** Pulls the central server list and applies administrative states newer than the local copy. */
    public void synchronize() {
        if (!apiClient.enabled()) {
            return;
        }
        apiClient.call("server.list", client -> client.network().listServers()).whenComplete((list, throwable) -> {
            if (throwable != null) {
                logger.debug("Server registry sync failed: {}", Throwables.rootMessage(throwable));
                return;
            }
            for (NetworkServerResponse response : list) {
                NetworkServer server = servers.get(response.serverId());
                if (server != null) {
                    applyRemote(server, response);
                }
            }
            lastSyncAt = Instant.now();
            centralStateLoaded = true;
            for (NetworkServer server : servers.values()) {
                if (server.stateVersion() < 0) {
                    pushRegistration(server);
                }
            }
        });
    }

    private void applyRemote(NetworkServer server, NetworkServerResponse response) {
        ServerAdminState state = ServerAdminState.find(response.adminState().name()).orElse(ServerAdminState.ONLINE);
        if (response.version() >= server.stateVersion()) {
            applyLocalState(server, state, response.stateChangedBy(), response.stateReason(), response.stateChangedAt(),
                    response.version());
        }
        server.applyRemotePlayerCount(response.playerCount(), Instant.now());
    }

    private void pushRegistration(NetworkServer server) {
        if (!apiClient.enabled()) {
            return;
        }
        ServerRegistrationRequest request = new ServerRegistrationRequest(server.serverId(),
                ServerTypeResponse.valueOf(server.type().name()), server.region(), server.capacity(), server.weight(),
                server.address(), server.tags(), identity.proxyId());
        apiClient.call("server.register", client -> client.network().registerServer(server.serverId(), request))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Central registration of {} failed: {}", server.serverId(), Throwables.rootMessage(throwable));
                        return;
                    }
                    applyRemote(server, response);
                    centralStateLoaded = true;
                });
    }
}
