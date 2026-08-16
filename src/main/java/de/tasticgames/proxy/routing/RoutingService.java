package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerHealthState;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Central routing engine: eligibility filter (admin state, health, capacity incl.
 * reservations and safety margin, region policy, exclusions) followed by deterministic
 * scoring (region preference, normalized load, configured weight, health, tie-break by id).
 * Every decision is fully explainable via {@link RoutingDecision#candidates()}.
 */
public final class RoutingService implements ProxyService {

    private final ServerRegistryService registry;
    private final CapacityReservationService reservations;
    private final ProxyConfigurationService configurationService;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final Logger logger;

    public RoutingService(ServerRegistryService registry, CapacityReservationService reservations,
                          ProxyConfigurationService configurationService, TelemetryService telemetry,
                          ProxyMetrics metrics, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "routing-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public RoutingDecision route(RoutingRequest request) {
        RoutingDecision decision = evaluate(request, configurationService.configuration().routing(), registry.servers());
        metrics.increment("routing.requests");
        if (decision.found()) {
            metrics.increment("routing.decisions");
            telemetry.publish(telemetry.event(TelemetryTypes.ROUTING_DECISION).player(request.playerUuid())
                    .server(decision.target().serverId()).outcome(request.reason())
                    .attribute("target", request.describeTarget()).attribute("groupSize", request.groupSize()).build());
        } else {
            metrics.increment("routing.no_target");
            telemetry.publish(telemetry.event(TelemetryTypes.ROUTING_NO_TARGET).player(request.playerUuid())
                    .outcome(request.reason()).attribute("target", request.describeTarget()).build());
            logger.debug("No routing target for {} ({}): {}", request.describeTarget(), request.reason(), decision.summary());
        }
        return decision;
    }

    /** Pure evaluation (unit-tested); does not publish telemetry. */
    public RoutingDecision evaluate(RoutingRequest request, ProxyConfiguration.Routing config,
                                    java.util.Collection<NetworkServer> servers) {
        return evaluate(request, config, servers, reservations);
    }

    /** Pure, dependency-free evaluation used by tests and diagnostics. */
    public static RoutingDecision evaluate(RoutingRequest request, ProxyConfiguration.Routing config,
                                           java.util.Collection<NetworkServer> servers, CapacityReservationService reservations) {
        String preferredRegion = request.preferredRegion() != null ? request.preferredRegion() : config.preferredRegion();
        List<RoutingCandidate> candidates = new ArrayList<>();
        for (NetworkServer server : servers) {
            candidates.add(evaluateServer(server, request, config, preferredRegion, reservations));
        }
        Comparator<RoutingCandidate> order = Comparator
                .comparing((RoutingCandidate c) -> c.eligible() ? 0 : 1)
                .thenComparing((RoutingCandidate c) -> -c.score())
                .thenComparing((RoutingCandidate c) -> c.server().serverId());
        candidates.sort(order);
        NetworkServer target = candidates.isEmpty() || !candidates.getFirst().eligible() ? null : candidates.getFirst().server();
        String summary = target == null
                ? "no eligible server for " + request.describeTarget() + " (" + candidates.size() + " evaluated)"
                : "selected " + target.serverId() + " with score " + String.format(Locale.ROOT, "%.1f", candidates.getFirst().score());
        return new RoutingDecision(request, target, candidates, summary);
    }

    private static RoutingCandidate evaluateServer(NetworkServer server, RoutingRequest request,
                                                   ProxyConfiguration.Routing config, String preferredRegion,
                                                   CapacityReservationService reservations) {
        if (request.targetServerId() != null && !server.serverId().equals(request.targetServerId())) {
            return RoutingCandidate.excluded(server, "not the requested server");
        }
        if (request.targetServerId() == null && server.type() != request.targetType()) {
            return RoutingCandidate.excluded(server, "type " + server.type() + " != " + request.targetType());
        }
        if (request.excludedServers().contains(server.serverId())) {
            return RoutingCandidate.excluded(server, "explicitly excluded");
        }
        if (!request.allowCurrent() && request.currentServer() != null && server.serverId().equals(request.currentServer())) {
            return RoutingCandidate.excluded(server, "player is already on this server");
        }
        switch (server.adminState()) {
            case DRAINING -> { return RoutingCandidate.excluded(server, "admin state DRAINING"); }
            case MAINTENANCE -> { return RoutingCandidate.excluded(server, "admin state MAINTENANCE"); }
            case OFFLINE -> { return RoutingCandidate.excluded(server, "admin state OFFLINE"); }
            case ONLINE -> { }
        }
        if (server.health() == ServerHealthState.UNREACHABLE) {
            return RoutingCandidate.excluded(server, "health UNREACHABLE (" + server.consecutiveFailures() + " failed pings)");
        }
        boolean sameRegion = server.region().equals(preferredRegion);
        if (!sameRegion && !config.crossRegionFallback()) {
            return RoutingCandidate.excluded(server, "region " + server.region() + " (cross-region routing disabled)");
        }
        int reserved = reservations.reserved(server.serverId());
        int free = server.freeSlots() - reserved - config.capacitySafetyMargin();
        if (server.capacity() > 0 && free < request.groupSize()) {
            return RoutingCandidate.excluded(server, "capacity: " + server.knownPlayerCount() + "/" + server.capacity()
                    + " (+" + reserved + " reserved, margin " + config.capacitySafetyMargin() + ") < " + request.groupSize());
        }

        double load = server.capacity() > 0 ? (double) (server.knownPlayerCount() + reserved) / server.capacity() : 0.0;
        double loadScore = (1.0 - Math.min(1.0, load)) * config.loadWeight();
        double regionScore = sameRegion ? config.regionWeight() : 0.0;
        double weightScore = server.weight() * config.serverWeightFactor() / 100.0;
        double healthScore = switch (server.health()) {
            case HEALTHY -> 10.0;
            case UNKNOWN -> 0.0;
            case DEGRADED -> -20.0;
            case UNREACHABLE -> -1000.0;
        };
        double score = loadScore + regionScore + weightScore + healthScore;
        String explanation = String.format(Locale.ROOT, "load=%.1f region=%s(%.0f) weight=%.1f health=%s(%.0f)",
                loadScore, sameRegion ? "same" : "other", regionScore, weightScore, server.health(), healthScore);
        return RoutingCandidate.eligible(server, score, explanation);
    }
}
