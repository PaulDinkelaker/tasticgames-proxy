package de.tasticgames.proxy.routing;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fallback 1.0: on backend kick/crash the player is routed to a healthy server of the
 * configured fallback type (normally LOBBY) via the routing engine. Loops are prevented:
 * the failed server is excluded, and a second fallback within a short window disconnects
 * the player with a localized message instead of bouncing forever.
 */
public final class FallbackService implements ProxyService {

    private static final Duration LOOP_WINDOW = Duration.ofSeconds(20);
    private static final int MAX_FALLBACKS_IN_WINDOW = 2;

    private final ProxyConfigurationService configurationService;
    private final RoutingService routing;
    private final ProxyMessages messages;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final Logger logger;
    private final FallbackLoopGuard loopGuard = new FallbackLoopGuard(LOOP_WINDOW, MAX_FALLBACKS_IN_WINDOW);

    public FallbackService(ProxyConfigurationService configurationService, RoutingService routing, ProxyMessages messages,
                           TelemetryService telemetry, ProxyMetrics metrics, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "fallback-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        loopGuard.clear();
    }

    public ServerType fallbackType() {
        return ServerType.find(configurationService.configuration().routing().fallbackServerType()).orElse(ServerType.LOBBY);
    }

    /** Chooses a fallback target for a kicked player (never the failed server). */
    public Optional<NetworkServer> chooseFallback(Player player, String failedServer) {
        RoutingRequest request = new RoutingRequest(player.getUniqueId(), fallbackType(), null, null, 1,
                failedServer == null ? Set.of() : Set.of(failedServer), null, true, TransferReason.FALLBACK);
        return routing.route(request).targetOptional();
    }

    /** Decides how a KickedFromServerEvent is handled. Pure decision (unit-tested via loop guard). */
    public KickedFromServerEvent.ServerKickResult decide(Player player, String failedServer, Component kickReason,
                                                          boolean duringConnect) {
        UUID uuid = player.getUniqueId();
        boolean loop = loopGuard.recordAndDetectLoop(uuid);
        telemetry.publish(telemetry.event(TelemetryTypes.FALLBACK_STARTED).player(uuid).server(failedServer)
                .attribute("duringConnect", duringConnect).attribute("loop", loop)
                .attribute("kickReason", kickReason == null ? "" : PlainTextComponentSerializer.plainText().serialize(kickReason)).build());
        metrics.increment("fallback.started");
        if (loop) {
            metrics.increment("fallback.failed");
            telemetry.publish(telemetry.event(TelemetryTypes.FALLBACK_FAILED).player(uuid).server(failedServer).outcome("LOOP").build());
            logger.warn("Fallback loop detected for {} (failed server {}); disconnecting.", player.getUsername(), failedServer);
            return KickedFromServerEvent.DisconnectPlayer.create(messages.get(player, "fallback.unavailable"));
        }
        Optional<NetworkServer> target = chooseFallback(player, failedServer);
        if (target.isEmpty()) {
            metrics.increment("fallback.failed");
            telemetry.publish(telemetry.event(TelemetryTypes.FALLBACK_FAILED).player(uuid).server(failedServer).outcome("NO_TARGET").build());
            return KickedFromServerEvent.DisconnectPlayer.create(messages.get(player, "fallback.unavailable"));
        }
        metrics.increment("fallback.success");
        telemetry.publish(telemetry.event(TelemetryTypes.FALLBACK_SUCCESS).player(uuid).server(target.get().serverId())
                .attribute("failedServer", failedServer).build());
        return KickedFromServerEvent.RedirectPlayer.create(target.get().registeredServer(),
                messages.get(player, "fallback.redirected", Map.of("server", failedServer == null ? "?" : failedServer)));
    }

    public void forget(UUID uuid) {
        loopGuard.forget(uuid);
    }
}
