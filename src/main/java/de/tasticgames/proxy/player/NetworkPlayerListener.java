package de.tasticgames.proxy.player;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.locale.PlayerLanguageService;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.presence.NetworkPresenceService;
import de.tasticgames.proxy.routing.FallbackService;
import de.tasticgames.proxy.routing.RoutingDecision;
import de.tasticgames.proxy.routing.RoutingRequest;
import de.tasticgames.proxy.routing.RoutingService;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Connection lifecycle: login gate telemetry, session creation, initial routing,
 * backend switches (session + presence), disconnect (idempotent cleanup).
 */
public final class NetworkPlayerListener {

    private final NetworkPlayerManager playerManager;
    private final NetworkPresenceService presenceService;
    private final RoutingService routingService;
    private final ProxyConfigurationService configurationService;
    private final PlayerLanguageService languageService;
    private final FallbackService fallbackService;
    private final ProxyMessages messages;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final Logger logger;
    private volatile Consumer<NetworkPlayerSession> joinHook = ignored -> { };
    private volatile Consumer<NetworkPlayerSession> quitHook = ignored -> { };
    private volatile Consumer<NetworkPlayerManager.ServerChange> switchHook = ignored -> { };

    public NetworkPlayerListener(NetworkPlayerManager playerManager, NetworkPresenceService presenceService,
                                 RoutingService routingService, ProxyConfigurationService configurationService,
                                 PlayerLanguageService languageService, FallbackService fallbackService,
                                 ProxyMessages messages, TelemetryService telemetry, ProxyMetrics metrics, Logger logger) {
        this.playerManager = Objects.requireNonNull(playerManager, "playerManager");
        this.presenceService = Objects.requireNonNull(presenceService, "presenceService");
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.languageService = Objects.requireNonNull(languageService, "languageService");
        this.fallbackService = Objects.requireNonNull(fallbackService, "fallbackService");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Hooks used by social services (friend join/quit notifications, party member offline). */
    public void onJoin(Consumer<NetworkPlayerSession> hook) { this.joinHook = Objects.requireNonNull(hook); }
    public void onQuit(Consumer<NetworkPlayerSession> hook) { this.quitHook = Objects.requireNonNull(hook); }
    public void onSwitch(Consumer<NetworkPlayerManager.ServerChange> hook) { this.switchHook = Objects.requireNonNull(hook); }

    @Subscribe(priority = -32767)
    public void onLoginMonitor(LoginEvent event) {
        boolean allowed = event.getResult().isAllowed();
        metrics.increment(allowed ? "connections.allowed" : "connections.denied");
        telemetry.publish(telemetry.event(allowed ? TelemetryTypes.PLAYER_CONNECTION_ALLOWED : TelemetryTypes.PLAYER_CONNECTION_DENIED)
                .player(event.getPlayer().getUniqueId()).build());
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        try {
            NetworkPlayerSession session = playerManager.connect(player);
            languageService.load(player.getUniqueId());
            metrics.increment("sessions.started");
            telemetry.publish(telemetry.event(TelemetryTypes.PLAYER_SESSION_STARTED).player(session.minecraftUuid())
                    .session(session.sessionId()).build());
            logger.info("Session {} started for {} [{}].", session.sessionId().toString().substring(0, 8),
                    session.username(), session.minecraftUuid());
        } catch (Exception exception) {
            logger.error("Failed to create network session for {} [{}].", player.getUsername(), player.getUniqueId(), exception);
        }
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        ServerType initialType = ServerType.find(configurationService.configuration().routing().initialServerType()).orElse(ServerType.LOBBY);
        RoutingRequest request = RoutingRequest.forType(player.getUniqueId(), initialType, null, TransferReason.INITIAL_JOIN);
        RoutingDecision decision = routingService.route(request);
        Optional<NetworkServer> target = decision.targetOptional();
        if (target.isEmpty() && initialType != fallbackService.fallbackType()) {
            target = routingService.route(RoutingRequest.forType(player.getUniqueId(), fallbackService.fallbackType(), null,
                    TransferReason.INITIAL_JOIN)).targetOptional();
        }
        if (target.isPresent()) {
            event.setInitialServer(target.get().registeredServer());
            return;
        }
        logger.warn("No initial server available for {} ({}); disconnecting.", player.getUsername(), decision.summary());
        event.setInitialServer(null);
        player.disconnect(messages.get(player, "routing.no_initial_server"));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String server = event.getServer().getServerInfo().getName();
        try {
            NetworkPlayerManager.ServerChange change = playerManager.serverConnected(uuid, server);
            if (change == null) {
                logger.warn("Player {} connected to {} without a network session (late join?); creating one.", player.getUsername(), server);
                playerManager.connect(player);
                change = playerManager.serverConnected(uuid, server);
            }
            NetworkPlayerSession session = change.session();
            session.updatePing(player.getPing());
            presenceService.connected(session, server, change.firstServer());
            metrics.increment(change.firstServer() ? "sessions.initial_connect" : "sessions.switch");
            if (!change.firstServer()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PLAYER_SERVER_SWITCH).player(uuid).session(session.sessionId())
                        .server(server).attribute("previous", change.previousServer()).build());
            }
            logger.info("{} connected to {}{}.", session.username(), server,
                    change.previousServer() == null ? "" : " from " + change.previousServer());
            switchHook.accept(change);
            if (change.firstServer()) {
                joinHook.accept(session);
            }
        } catch (Exception exception) {
            logger.error("Failed to update network state for {} [{}] on {}.", player.getUsername(), uuid, server, exception);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        languageService.forget(uuid);
        fallbackService.forget(uuid);
        Optional<NetworkPlayerSession> removed = playerManager.disconnect(uuid);
        if (removed.isEmpty()) {
            return; // never had a session (denied login) – nothing to clean up
        }
        NetworkPlayerSession session = removed.get();
        metrics.increment("sessions.ended");
        telemetry.publish(telemetry.event(TelemetryTypes.PLAYER_SESSION_ENDED).player(uuid).session(session.sessionId())
                .server(session.currentServer().orElse(null))
                .duration(java.time.Duration.between(session.connectedAt(), java.time.Instant.now()).toMillis())
                .attribute("loginStatus", event.getLoginStatus()).build());
        if (session.connectedToServer() || session.currentServer().isPresent()) {
            presenceService.disconnected(session);
        }
        logger.info("Session {} ended for {} [{}] ({}).", session.sessionId().toString().substring(0, 8),
                session.username(), uuid, event.getLoginStatus());
        quitHook.accept(session);
    }
}
