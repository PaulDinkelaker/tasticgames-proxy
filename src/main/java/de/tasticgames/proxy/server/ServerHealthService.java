package de.tasticgames.proxy.server;

import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tasticgames.client.dto.network.ServerHealthReportRequest;
import de.tasticgames.client.dto.network.ServerHealthResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pings every registered backend asynchronously and derives HEALTHY/DEGRADED/UNREACHABLE
 * with failure/recovery thresholds (no flapping on a single lost ping). Health changes are
 * logged, published as telemetry and reported to the API as proxy observations.
 */
public final class ServerHealthService implements ProxyService {

    private final ServerRegistryService registry;
    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final ProxyIdentity identity;
    private final Logger logger;
    private final AtomicInteger cycle = new AtomicInteger();

    public ServerHealthService(ServerRegistryService registry, ProxyConfigurationService configurationService,
                               ProxyApiClient apiClient, ProxyScheduler scheduler, TelemetryService telemetry,
                               ProxyMetrics metrics, ProxyIdentity identity, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "server-health-service";
    }

    @Override
    public void start() {
        Duration interval = configurationService.configuration().health().pingInterval();
        scheduler.repeat("server-health", Duration.ofSeconds(1), interval, this::pingAll);
    }

    @Override
    public void stop() {
    }

    public void pingAll() {
        int currentCycle = cycle.incrementAndGet();
        for (NetworkServer server : registry.servers()) {
            ping(server, currentCycle);
        }
    }

    public void ping(NetworkServer server, int currentCycle) {
        ProxyConfiguration.Health config = configurationService.configuration().health();
        long start = System.nanoTime();
        server.registeredServer().ping(PingOptions.builder().timeout(config.pingTimeout()).build())
                .whenComplete((ping, throwable) -> {
                    Instant now = Instant.now();
                    if (throwable != null) {
                        server.recordPingFailure(Throwables.rootMessage(throwable), now);
                        metrics.increment("health.ping_failures");
                    } else {
                        long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        int players = ping == null ? 0 : ping.getPlayers().map(ServerPing.Players::getOnline).orElse(0);
                        server.recordPingSuccess(latency, players, now);
                        metrics.increment("health.ping_successes");
                    }
                    evaluate(server, config, now, currentCycle);
                });
    }

    private void evaluate(NetworkServer server, ProxyConfiguration.Health config, Instant now, int currentCycle) {
        ServerHealthState next = nextState(server.health(), server.consecutiveFailures(), server.consecutiveSuccesses(),
                server.latencyMillis(), config);

        ServerHealthState previous = server.applyHealth(next, now);
        boolean changed = previous != null;
        if (changed) {
            if (next == ServerHealthState.UNREACHABLE) {
                logger.warn("Server {} health {} -> {} ({} consecutive failures: {}).", server.serverId(), previous, next,
                        server.consecutiveFailures(), server.lastFailureMessage());
            } else {
                logger.info("Server {} health {} -> {} (latency {} ms).", server.serverId(), previous, next, server.latencyMillis());
            }
            telemetry.publish(telemetry.event(TelemetryTypes.SERVER_HEALTH_CHANGED).server(server.serverId())
                    .outcome(next).attribute("previous", previous).attribute("latency", server.latencyMillis()).build());
        }
        // report on change and every 3rd cycle to keep the central observation fresh
        if (changed || currentCycle % 3 == 0) {
            report(server, now);
        }
    }

    /**
     * Pure health state machine (unit-tested):
     * <ul>
     *   <li>failures >= failureThreshold: UNREACHABLE</li>
     *   <li>a failure below the threshold: UNKNOWN stays UNKNOWN, UNREACHABLE stays, otherwise DEGRADED</li>
     *   <li>success while UNREACHABLE: only trusted after recoveryThreshold successes</li>
     *   <li>otherwise HEALTHY, or DEGRADED when the latency exceeds the configured limit</li>
     * </ul>
     */
    static ServerHealthState nextState(ServerHealthState current, int failures, int successes, long latencyMillis,
                                       ProxyConfiguration.Health config) {
        if (failures >= config.failureThreshold()) {
            return ServerHealthState.UNREACHABLE;
        }
        if (failures > 0) {
            return switch (current) {
                case UNKNOWN -> ServerHealthState.UNKNOWN;
                case UNREACHABLE -> ServerHealthState.UNREACHABLE;
                default -> ServerHealthState.DEGRADED;
            };
        }
        if (current == ServerHealthState.UNREACHABLE && successes < config.recoveryThreshold()) {
            return ServerHealthState.UNREACHABLE;
        }
        return latencyMillis > config.degradedLatency().toMillis() ? ServerHealthState.DEGRADED : ServerHealthState.HEALTHY;
    }

    private void report(NetworkServer server, Instant now) {
        if (!apiClient.enabled()) {
            return;
        }
        ServerHealthReportRequest request = new ServerHealthReportRequest(identity.proxyId(),
                ServerHealthResponse.valueOf(server.health().name()),
                server.latencyMillis() < 0 ? null : server.latencyMillis(),
                server.localPlayerCount(), now);
        apiClient.call("server.health", client -> client.network().reportServerHealth(server.serverId(), request))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Health report for {} failed: {}", server.serverId(), Throwables.rootMessage(throwable));
                    } else {
                        server.applyRemotePlayerCount(response.playerCount(), Instant.now());
                    }
                });
    }
}
