package de.tasticgames.proxy.identity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.network.ProxyHeartbeatRequest;
import de.tasticgames.client.dto.network.ProxyInstanceResponse;
import de.tasticgames.client.dto.network.ProxyRegistrationRequest;
import de.tasticgames.client.dto.network.ProxyStatusResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Registers this proxy instance centrally, sends heartbeats and marks the instance OFFLINE
 * on shutdown (best effort, bounded). Exposes the known proxy list for diagnostics.
 */
public final class ProxyRegistryService implements ProxyService {

    private final ProxyServer proxyServer;
    private final ProxyIdentity identity;
    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final Logger logger;

    private volatile ProxyStatusResponse status = ProxyStatusResponse.STARTING;
    private volatile boolean registered;
    private volatile Instant lastHeartbeatAt;
    private volatile List<ProxyInstanceResponse> knownProxies = List.of();

    public ProxyRegistryService(ProxyServer proxyServer, ProxyIdentity identity, ProxyConfigurationService configurationService,
                                ProxyApiClient apiClient, ProxyScheduler scheduler, TelemetryService telemetry,
                                ProxyMetrics metrics, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "proxy-registry-service";
    }

    @Override
    public void start() {
        Duration interval = configurationService.configuration().identity().heartbeatInterval();
        register();
        scheduler.repeat("proxy-heartbeat", interval, interval, this::heartbeat);
        scheduler.repeat("proxy-list", Duration.ofSeconds(30), Duration.ofSeconds(30), this::refreshKnownProxies);
    }

    @Override
    public void stop() {
        status = ProxyStatusResponse.OFFLINE;
        if (!apiClient.enabled() || !registered) {
            return;
        }
        try {
            apiClient.call("proxy.offline", client -> client.network().updateProxyStatus(identity.proxyId(), ProxyStatusResponse.OFFLINE))
                    .get(3, TimeUnit.SECONDS);
            logger.info("Proxy {} marked OFFLINE centrally.", identity.proxyId());
        } catch (Exception e) {
            logger.warn("Could not mark proxy OFFLINE centrally (stale detection will handle it): {}", Throwables.rootMessage(e));
        }
    }

    public ProxyIdentity identity() {
        return identity;
    }

    public ProxyStatusResponse status() {
        return status;
    }

    public boolean registered() {
        return registered;
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public List<ProxyInstanceResponse> knownProxies() {
        return knownProxies;
    }

    public void markOnline() {
        if (status == ProxyStatusResponse.STARTING) {
            status = ProxyStatusResponse.ONLINE;
            heartbeat();
        }
    }

    public void markDraining() {
        status = ProxyStatusResponse.DRAINING;
        heartbeat();
    }

    private void register() {
        if (!apiClient.enabled()) {
            return;
        }
        ProxyRegistrationRequest request = new ProxyRegistrationRequest(identity.region(), identity.environment(),
                identity.version(), identity.host().isBlank() ? null : identity.host(), identity.startedAt(),
                proxyServer.getPlayerCount());
        apiClient.call("proxy.register", client -> client.network().registerProxy(identity.proxyId(), request))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Proxy registration failed (will retry with heartbeat): {}", Throwables.rootMessage(throwable));
                        return;
                    }
                    registered = true;
                    logger.info("Proxy {} registered centrally (region {}, environment {}).", identity.proxyId(),
                            identity.region(), identity.environment());
                    telemetry.publish(telemetry.event(TelemetryTypes.PROXY_STARTED).attribute("version", identity.version()).build());
                    heartbeat();
                });
    }

    public CompletableFuture<Void> heartbeat() {
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!registered) {
            register();
            return CompletableFuture.completedFuture(null);
        }
        ProxyHeartbeatRequest request = new ProxyHeartbeatRequest(status, proxyServer.getPlayerCount(), identity.version());
        return apiClient.call("proxy.heartbeat", client -> client.network().heartbeat(identity.proxyId(), request))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = Throwables.unwrap(throwable);
                        if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 404) {
                            registered = false; // API lost us (e.g. DB reset) – re-register next tick
                        }
                        logger.debug("Proxy heartbeat failed: {}", Throwables.rootMessage(cause));
                        return null;
                    }
                    lastHeartbeatAt = Instant.now();
                    metrics.increment("proxy.heartbeats");
                    return null;
                });
    }

    private void refreshKnownProxies() {
        if (!apiClient.enabled()) {
            return;
        }
        apiClient.call("proxy.list", client -> client.network().listProxies()).whenComplete((list, throwable) -> {
            if (throwable == null) {
                knownProxies = List.copyOf(list);
                metrics.gauge("proxies.known", list.size());
            }
        });
    }
}
