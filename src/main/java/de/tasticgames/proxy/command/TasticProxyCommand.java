package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.network.ProxyInstanceResponse;
import de.tasticgames.proxy.access.AlphaAccessService;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.diagnostics.ProxyBuildInfo;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.identity.ProxyRegistryService;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.maintenance.MaintenanceService;
import de.tasticgames.proxy.player.NetworkPlayerManager;
import de.tasticgames.proxy.player.NetworkPlayerSession;
import de.tasticgames.proxy.presence.NetworkPresenceService;
import de.tasticgames.proxy.routing.CapacityReservationService;
import de.tasticgames.proxy.routing.RoutingCandidate;
import de.tasticgames.proxy.routing.RoutingDecision;
import de.tasticgames.proxy.routing.RoutingRequest;
import de.tasticgames.proxy.routing.RoutingService;
import de.tasticgames.proxy.routing.TransferOperation;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.routing.TransferService;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerAdminState;
import de.tasticgames.proxy.server.ServerHealthState;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.social.clan.ClanService;
import de.tasticgames.proxy.social.party.PartyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Throwables;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * /tasticproxy status|health|reload|player <name>|route <LOBBY|SURVIVAL|server> [player]|proxies|transfers
 */
public final class TasticProxyCommand extends CommandSupport {

    public record Dependencies(
            ProxyServer proxyServer,
            ProxyIdentity identity,
            ProxyBuildInfo buildInfo,
            ProxyConfigurationService configurationService,
            ProxyApiClient apiClient,
            ProxyMetrics metrics,
            ProxyRegistryService proxyRegistry,
            ServerRegistryService serverRegistry,
            NetworkPlayerManager playerManager,
            NetworkPresenceService presenceService,
            RoutingService routingService,
            TransferService transferService,
            CapacityReservationService reservations,
            NetworkCommandBus bus,
            TelemetryService telemetryService,
            MaintenanceService maintenanceService,
            AlphaAccessService alphaAccessService,
            PartyService partyService,
            ClanService clanService,
            SocialPlayerLookup lookup,
            Runnable reloadHook
    ) {
    }

    private final Dependencies d;

    public TasticProxyCommand(Dependencies dependencies, ProxyMessages messages, Logger logger) {
        super(messages, dependencies.telemetryService(), logger);
        this.d = Objects.requireNonNull(dependencies);
    }

    @Override
    protected String commandName() {
        return "tasticproxy";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource s = invocation.source();
        return s.hasPermission(ProxyPermissions.ADMIN) || s.hasPermission(ProxyPermissions.STATUS)
                || s.hasPermission(ProxyPermissions.PLAYER_INSPECT) || s.hasPermission(ProxyPermissions.ROUTING_DIAGNOSE);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                if (requireAny(source, ProxyPermissions.STATUS)) status(source);
            }
            case "health" -> {
                if (requireAny(source, ProxyPermissions.STATUS)) health(source);
            }
            case "reload" -> {
                if (requireAny(source, ProxyPermissions.RELOAD)) reload(source);
            }
            case "player" -> {
                if (requireAny(source, ProxyPermissions.PLAYER_INSPECT)) player(source, args);
            }
            case "route" -> {
                if (requireAny(source, ProxyPermissions.ROUTING_DIAGNOSE)) route(source, args);
            }
            case "proxies" -> {
                if (requireAny(source, ProxyPermissions.STATUS)) proxies(source);
            }
            case "transfers" -> {
                if (requireAny(source, ProxyPermissions.STATUS)) transfers(source);
            }
            default -> send(source, "tasticproxy.usage");
        }
    }

    private boolean requireAny(CommandSource source, String permission) {
        if (source.hasPermission(ProxyPermissions.ADMIN) || source.hasPermission(permission)) {
            return true;
        }
        send(source, "command.no_permission");
        return false;
    }

    private void status(CommandSource source) {
        ProxyMetrics m = d.metrics();
        long healthy = d.serverRegistry().servers().stream().filter(s -> s.health() == ServerHealthState.HEALTHY).count();
        long draining = d.serverRegistry().servers().stream().filter(NetworkServer::draining).count();
        long unreachable = d.serverRegistry().servers().stream().filter(s -> s.health() == ServerHealthState.UNREACHABLE).count();
        String apiStatus = !d.apiClient().enabled() ? "DISABLED" : d.apiClient().healthy() ? "OK" : "DEGRADED (" + d.apiClient().consecutiveFailures() + " failures)";
        header(source, "TasticProxy " + d.buildInfo().version());
        line(source, "Build", d.buildInfo().buildTimestamp() + " " + d.buildInfo().gitCommit());
        line(source, "Uptime", formatDuration(Duration.between(d.identity().startedAt(), Instant.now())));
        line(source, "Proxy", d.identity().proxyId() + " (" + d.identity().proxyName() + ")");
        line(source, "Region / Env", d.identity().region() + " / " + d.identity().environment());
        line(source, "Central status", d.proxyRegistry().status() + (d.proxyRegistry().registered() ? "" : " (not registered)")
                + ", heartbeat " + ago(d.proxyRegistry().lastHeartbeatAt()));
        line(source, "API", apiStatus + (d.apiClient().lastLatencyMillis() >= 0 ? ", " + d.apiClient().lastLatencyMillis() + " ms" : "")
                + " | ok=" + m.counter("api.successes") + " fail=" + m.counter("api.failures"));
        line(source, "Maintenance", (d.maintenanceService().enabled() ? "ENABLED" : "disabled") + " (" + (d.maintenanceService().centralLoaded() ? "central" : "cache") + ")");
        line(source, "Alpha access", (d.alphaAccessService().enabled() ? "ENABLED" : "disabled") + ", " + d.alphaAccessService().allowedCount() + " entries" + (d.alphaAccessService().stateKnown() ? "" : ", STATE UNKNOWN"));
        line(source, "Players", d.proxyServer().getPlayerCount() + " online, " + d.playerManager().onlineCount() + " sessions");
        line(source, "Servers", d.serverRegistry().size() + " registered, " + healthy + " healthy, " + draining + " draining, " + unreachable + " unreachable"
                + " (sync " + (d.serverRegistry().centralStateLoaded() ? ago(d.serverRegistry().lastSyncAt()) : "pending") + ")");
        line(source, "Health checks", "ok=" + m.counter("health.ping_successes") + " fail=" + m.counter("health.ping_failures"));
        line(source, "Routing", "requests=" + m.counter("routing.requests") + " no-target=" + m.counter("routing.no_target"));
        line(source, "Transfers", "active=" + d.transferService().activeTransfers().size() + " ok=" + m.counter("transfers.success")
                + " failed=" + m.counter("transfers.failed") + " rejected=" + m.counter("transfers.rejected")
                + " reservations=" + d.reservations().activeReservations());
        line(source, "Fallback", "started=" + m.counter("fallback.started") + " ok=" + m.counter("fallback.success") + " failed=" + m.counter("fallback.failed"));
        line(source, "Presence", "ok=" + m.counter("presence.successes") + " fail=" + m.counter("presence.failures"));
        line(source, "Command bus", (d.bus().available() ? "API polling" : "local only") + " processed=" + d.bus().processedCount()
                + " failed=" + d.bus().failedCount() + " lastBatch=" + d.bus().pendingLocalCommands());
        line(source, "Telemetry", "queue=" + d.telemetryService().queueSize() + " published=" + d.telemetryService().publishedCount()
                + " dropped=" + d.telemetryService().droppedCount() + " failures=" + d.telemetryService().consecutiveFailures());
    }

    private void health(CommandSource source) {
        header(source, "TasticProxy health report");
        line(source, "API", d.apiClient().enabled()
                ? (d.apiClient().healthy() ? "healthy" : "unhealthy: " + d.apiClient().lastFailureMessage()) + ", last success " + ago(d.apiClient().lastSuccessAt())
                : "disabled");
        for (NetworkServer server : d.serverRegistry().servers().stream().sorted(java.util.Comparator.comparing(NetworkServer::serverId)).toList()) {
            NamedTextColor color = server.health() == ServerHealthState.HEALTHY ? NamedTextColor.GREEN
                    : server.health() == ServerHealthState.DEGRADED ? NamedTextColor.YELLOW
                    : server.health() == ServerHealthState.UNREACHABLE ? NamedTextColor.RED : NamedTextColor.GRAY;
            source.sendMessage(Component.text(" • " + server.serverId() + " ", NamedTextColor.WHITE)
                    .append(Component.text(server.adminState() + "/" + server.health(), color))
                    .append(Component.text(" latency=" + (server.latencyMillis() < 0 ? "-" : server.latencyMillis() + "ms")
                            + " lastCheck=" + ago(server.lastHealthCheckAt()) + " lastHealthy=" + ago(server.lastHealthyAt())
                            + " failures=" + server.consecutiveFailures()
                            + (server.lastFailureMessage().isBlank() ? "" : " (" + server.lastFailureMessage() + ")"), NamedTextColor.GRAY)));
        }
        line(source, "Scheduler", "bus lastPoll " + (d.bus() instanceof de.tasticgames.proxy.bus.ApiNetworkCommandBus b ? ago(b.lastPollAt()) : "n/a"));
        line(source, "Maintenance", "refresh " + ago(d.maintenanceService().lastRefreshAt()));
        line(source, "Alpha access", "refresh " + ago(d.alphaAccessService().lastRefreshAt()) + ", version " + d.alphaAccessService().version());
    }

    private void reload(CommandSource source) {
        adminTelemetry(source, "reload", "");
        try {
            List<String> changed = d.configurationService().reload();
            d.reloadHook().run();
            send(source, "tasticproxy.reloaded", Map.of("sections", changed.isEmpty() ? "no changes" : String.join(", ", changed)));
        } catch (Exception e) {
            logger.error("Configuration reload failed: {}", Throwables.rootMessage(e));
            send(source, "tasticproxy.reload_failed", Map.of("error", Throwables.rootMessage(e)));
        }
    }

    private void player(CommandSource source, String[] args) {
        if (args.length < 2) {
            send(source, "tasticproxy.usage");
            return;
        }
        async(source, d.lookup().byNameOrUuid(args[1]), ref -> {
            if (ref.isEmpty()) {
                send(source, "common.player_not_found", Map.of("player", args[1]));
                return;
            }
            UUID uuid = ref.get().uuid();
            header(source, "Player " + ref.get().name());
            line(source, "UUID", uuid.toString());
            NetworkPlayerSession session = d.playerManager().find(uuid).orElse(null);
            if (session != null) {
                line(source, "Local session", session.sessionId() + " state=" + session.state() + " since " + ago(session.connectedAt()));
                line(source, "Backend", session.currentServer().orElse("-") + " (previous " + session.previousServer().orElse("-") + ", ping " + session.pingSnapshot() + "ms)");
                line(source, "Pending transfer", session.pendingTransferId().map(UUID::toString).orElse("-"));
            } else {
                line(source, "Local session", "not on this proxy");
            }
            async(source, d.presenceService().lookup(uuid), presence -> {
                if (presence.isPresent()) {
                    var p = presence.get();
                    line(source, "Presence", (p.online() ? "ONLINE" : "offline") + " proxy=" + p.proxyId() + " region=" + p.proxyRegion()
                            + " server=" + p.currentServer() + " (" + p.currentServerType() + ") lastSeen " + ago(p.lastSeenAt()));
                } else {
                    line(source, "Presence", "unknown account");
                }
            });
            async(source, d.partyService().partyOf(uuid), party -> line(source, "Party",
                    party.map(p -> p.partyId() + " (" + p.size() + " members, leader " + p.leaderUuid() + ")").orElse("-")));
            async(source, d.clanService().clanOf(uuid), clan -> line(source, "Clan",
                    clan.map(c -> c.name() + " (" + c.clanId() + ")").orElse("-")));
        });
    }

    private void route(CommandSource source, String[] args) {
        if (args.length < 2) {
            send(source, "tasticproxy.usage");
            return;
        }
        ServerType type = ServerType.find(args[1]).orElse(null);
        String serverId = type == null ? args[1].toLowerCase(Locale.ROOT) : null;
        UUID playerUuid = null;
        String current = null;
        int groupSize = 1;
        if (args.length >= 3) {
            Player target = d.proxyServer().getPlayer(args[2]).orElse(null);
            if (target != null) {
                playerUuid = target.getUniqueId();
                current = target.getCurrentServer().map(c -> c.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse(null);
            } else {
                try {
                    groupSize = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    // not a group size either
                }
            }
        }
        RoutingRequest request = new RoutingRequest(playerUuid, type, serverId, null, groupSize, java.util.Set.of(), current, false, TransferReason.ADMIN);
        RoutingDecision decision = d.routingService().evaluate(request, d.configurationService().configuration().routing(), d.serverRegistry().servers());
        header(source, "Routing " + request.describeTarget() + " (group " + groupSize + ")");
        line(source, "Result", decision.summary());
        for (RoutingCandidate candidate : decision.candidates()) {
            if (candidate.eligible()) {
                source.sendMessage(Component.text(" ✔ " + candidate.server().serverId(), NamedTextColor.GREEN)
                        .append(Component.text(String.format(Locale.ROOT, " score=%.1f %s", candidate.score(), candidate.scoreExplanation()), NamedTextColor.GRAY)));
            } else {
                source.sendMessage(Component.text(" ✘ " + candidate.server().serverId(), NamedTextColor.RED)
                        .append(Component.text(" " + candidate.exclusionReason(), NamedTextColor.GRAY)));
            }
        }
    }

    private void proxies(CommandSource source) {
        List<ProxyInstanceResponse> proxies = d.proxyRegistry().knownProxies();
        header(source, "Proxy instances (" + proxies.size() + ")");
        for (ProxyInstanceResponse proxy : proxies) {
            NamedTextColor color = proxy.stale() ? NamedTextColor.RED : proxy.status().name().equals("ONLINE") ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
            source.sendMessage(Component.text(" • " + proxy.proxyId(), NamedTextColor.WHITE)
                    .append(Component.text(" " + proxy.status() + (proxy.stale() ? " STALE" : ""), color))
                    .append(Component.text(" " + proxy.region() + "/" + proxy.environment() + " v" + proxy.version() + " players=" + proxy.playerCount()
                            + " heartbeat " + ago(proxy.lastHeartbeatAt()) + (proxy.proxyId().equals(d.identity().proxyId()) ? " (this)" : ""), NamedTextColor.GRAY)));
        }
    }

    private void transfers(CommandSource source) {
        header(source, "Transfers");
        for (TransferOperation op : d.transferService().activeTransfers()) {
            line(source, "ACTIVE " + op.playerName(), op.source() + " -> " + op.target() + " " + op.status() + " " + op.reason() + " " + op.durationMillis() + "ms");
        }
        d.transferService().recentTransfers().stream().sorted(java.util.Comparator.comparing(TransferOperation::createdAt).reversed()).limit(15)
                .forEach(op -> line(source, op.playerName(), op.source() + " -> " + op.target() + " " + op.status() + " " + op.reason() + " " + ago(op.createdAt())));
    }

    private void header(CommandSource source, String title) {
        source.sendMessage(Component.text("── " + title + " ──", NamedTextColor.GOLD));
    }

    private void line(CommandSource source, String label, Object value) {
        source.sendMessage(Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(String.valueOf(value), NamedTextColor.WHITE)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(List.of("status", "health", "reload", "player", "route", "proxies", "transfers"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return filter(d.proxyServer().getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("route")) {
            List<String> options = new java.util.ArrayList<>(List.of("LOBBY", "SURVIVAL", "CREATIVE", "DUELS"));
            d.serverRegistry().servers().forEach(s -> options.add(s.serverId()));
            return filter(options, args[1]);
        }
        return List.of();
    }

    static boolean isState(String value) {
        return ServerAdminState.find(value).isPresent();
    }
}
