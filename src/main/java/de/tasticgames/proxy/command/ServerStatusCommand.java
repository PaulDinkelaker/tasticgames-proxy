package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.server.DrainService;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerAdminState;
import de.tasticgames.proxy.server.ServerHealthService;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * /serverstatus [server] [state <ONLINE|MAINTENANCE|OFFLINE> | drain [migrate] | undrain | health]
 */
public final class ServerStatusCommand extends CommandSupport {

    private final ServerRegistryService registry;
    private final DrainService drainService;
    private final ServerHealthService healthService;

    public ServerStatusCommand(ServerRegistryService registry, DrainService drainService, ServerHealthService healthService,
                               ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.registry = Objects.requireNonNull(registry);
        this.drainService = Objects.requireNonNull(drainService);
        this.healthService = Objects.requireNonNull(healthService);
    }

    @Override
    protected String commandName() {
        return "serverstatus";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyPermissions.SERVER_STATUS);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePermission(source, ProxyPermissions.SERVER_STATUS)) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            list(source);
            return;
        }
        NetworkServer server = registry.find(args[0]).orElse(null);
        if (server == null) {
            send(source, "server.command.unknown", Map.of("server", args[0]));
            return;
        }
        if (args.length == 1) {
            details(source, server);
            return;
        }
        if (!requirePermission(source, ProxyPermissions.SERVER_MANAGE)) {
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        switch (action) {
            case "state" -> {
                ServerAdminState state = args.length >= 3 ? ServerAdminState.find(args[2]).orElse(null) : null;
                if (state == null) {
                    send(source, "server.command.usage");
                    return;
                }
                adminTelemetry(source, "state", server.serverId() + " -> " + state);
                if (state == ServerAdminState.DRAINING) {
                    async(source, drainService.beginDrain(server.serverId(), actor(source), false),
                            started -> send(source, "server.command.drain_started", Map.of("server", server.serverId())));
                    return;
                }
                async(source, registry.setState(server.serverId(), state, actor(source), "command"),
                        s -> send(source, "server.command.state_changed", Map.of("server", s.serverId(), "state", s.adminState())));
            }
            case "drain" -> {
                boolean migrate = args.length >= 3 && args[2].equalsIgnoreCase("migrate");
                adminTelemetry(source, "drain", server.serverId() + (migrate ? " migrate" : ""));
                async(source, drainService.beginDrain(server.serverId(), actor(source), migrate),
                        started -> send(source, started ? "server.command.drain_started" : "server.command.drain_already",
                                Map.of("server", server.serverId())));
            }
            case "undrain", "online" -> {
                adminTelemetry(source, "undrain", server.serverId());
                async(source, drainService.endDrain(server.serverId(), actor(source)).thenCompose(changed -> changed
                                ? java.util.concurrent.CompletableFuture.completedFuture(server)
                                : registry.setState(server.serverId(), ServerAdminState.ONLINE, actor(source), "command")),
                        s -> send(source, "server.command.state_changed", Map.of("server", server.serverId(), "state", "ONLINE")));
            }
            case "health" -> {
                healthService.ping(server, 0);
                send(source, "server.command.health_requested", Map.of("server", server.serverId()));
            }
            default -> send(source, "server.command.usage");
        }
    }

    private void list(CommandSource source) {
        List<NetworkServer> servers = registry.servers().stream().sorted(Comparator.comparing(NetworkServer::serverId)).toList();
        send(source, "server.command.list_header", Map.of("count", servers.size(),
                "sync", registry.centralStateLoaded() ? ago(registry.lastSyncAt()) : "not synced"));
        for (NetworkServer server : servers) {
            source.sendMessage(line(server));
        }
    }

    private Component line(NetworkServer server) {
        NamedTextColor stateColor = switch (server.adminState()) {
            case ONLINE -> NamedTextColor.GREEN;
            case DRAINING -> NamedTextColor.YELLOW;
            case MAINTENANCE -> NamedTextColor.GOLD;
            case OFFLINE -> NamedTextColor.RED;
        };
        NamedTextColor healthColor = switch (server.health()) {
            case HEALTHY -> NamedTextColor.GREEN;
            case DEGRADED -> NamedTextColor.YELLOW;
            case UNREACHABLE -> NamedTextColor.RED;
            case UNKNOWN -> NamedTextColor.GRAY;
        };
        return Component.text(" • ", NamedTextColor.DARK_GRAY)
                .append(Component.text(server.serverId(), NamedTextColor.WHITE))
                .append(Component.text(" " + server.type() + " " + server.region() + " ", NamedTextColor.GRAY))
                .append(Component.text(server.adminState().name(), stateColor))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                .append(Component.text(server.health().name(), healthColor))
                .append(Component.text(" " + server.knownPlayerCount() + "/" + server.capacity()
                        + (server.localPlayerCount() != server.knownPlayerCount() ? " (local " + server.localPlayerCount() + ")" : "")
                        + (server.latencyMillis() >= 0 ? " " + server.latencyMillis() + "ms" : ""), NamedTextColor.AQUA));
    }

    private void details(CommandSource source, NetworkServer server) {
        send(source, "server.command.details", Map.ofEntries(
                Map.entry("server", server.serverId()),
                Map.entry("type", server.type()),
                Map.entry("region", server.region()),
                Map.entry("state", server.adminState()),
                Map.entry("stateBy", server.stateChangedBy()),
                Map.entry("stateAt", ago(server.stateChangedAt())),
                Map.entry("reason", server.stateReason().isBlank() ? "-" : server.stateReason()),
                Map.entry("health", server.health()),
                Map.entry("healthAt", ago(server.healthChangedAt())),
                Map.entry("lastCheck", ago(server.lastHealthCheckAt())),
                Map.entry("lastHealthy", ago(server.lastHealthyAt())),
                Map.entry("latency", server.latencyMillis() < 0 ? "-" : server.latencyMillis() + " ms"),
                Map.entry("failures", server.consecutiveFailures()),
                Map.entry("players", server.knownPlayerCount()),
                Map.entry("local", server.localPlayerCount()),
                Map.entry("capacity", server.capacity()),
                Map.entry("weight", server.weight()),
                Map.entry("address", server.address()),
                Map.entry("tags", server.tags().isEmpty() ? "-" : String.join(",", server.tags())),
                Map.entry("draining", server.draining())));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(registry.servers().stream().map(NetworkServer::serverId).sorted().toList(), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            return filter(List.of("state", "drain", "undrain", "health"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("state")) {
            return filter(List.of("ONLINE", "MAINTENANCE", "OFFLINE", "DRAINING"), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("drain")) {
            return filter(List.of("migrate"), args[2]);
        }
        return List.of();
    }
}
