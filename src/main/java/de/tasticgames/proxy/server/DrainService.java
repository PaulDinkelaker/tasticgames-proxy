package de.tasticgames.proxy.server;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.routing.RoutingRequest;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.routing.TransferResult;
import de.tasticgames.proxy.routing.TransferService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Server drain 1.0: DRAINING excludes the server from routing, existing players may be
 * migrated to another instance of the same type (or the fallback type), and once the
 * server is empty network-wide it is set OFFLINE (deployment-ready). State is central.
 */
public final class DrainService implements ProxyService {

    private final ServerRegistryService registry;
    private final TransferService transferService;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final Logger logger;

    public DrainService(ServerRegistryService registry, TransferService transferService, ProxyScheduler scheduler,
                        TelemetryService telemetry, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transferService = Objects.requireNonNull(transferService, "transferService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "drain-service";
    }

    @Override
    public void start() {
        scheduler.repeat("drain-check", Duration.ofSeconds(15), Duration.ofSeconds(15), this::checkAll);
    }

    @Override
    public void stop() {
    }

    /** @return true when the drain was newly started */
    public CompletableFuture<Boolean> beginDrain(String serverName, String by, boolean migratePlayers) {
        NetworkServer server = registry.require(serverName);
        if (server.draining()) {
            checkServer(server);
            return CompletableFuture.completedFuture(false);
        }
        return registry.setState(server.serverId(), ServerAdminState.DRAINING, by, "drain").thenApply(s -> {
            logger.info("Drain started for {} with {} local player(s) remaining.", s.serverId(), s.localPlayerCount());
            telemetry.publish(telemetry.event(TelemetryTypes.SERVER_DRAIN_STARTED).server(s.serverId())
                    .attribute("by", by).attribute("players", s.localPlayerCount()).build());
            if (migratePlayers) {
                migrate(s);
            }
            checkServer(s);
            return true;
        });
    }

    public CompletableFuture<Boolean> endDrain(String serverName, String by) {
        NetworkServer server = registry.require(serverName);
        if (!server.draining() && !server.offline()) {
            return CompletableFuture.completedFuture(false);
        }
        return registry.setState(server.serverId(), ServerAdminState.ONLINE, by, "undrain").thenApply(s -> true);
    }

    /** Migrates all local players of a draining server to another server of the same type (fallback: lobby type). */
    public List<CompletableFuture<TransferResult>> migrate(NetworkServer server) {
        List<CompletableFuture<TransferResult>> futures = new ArrayList<>();
        for (Player player : server.registeredServer().getPlayersConnected()) {
            RoutingRequest request = new RoutingRequest(player.getUniqueId(), server.type(), null, null, 1,
                    Set.of(server.serverId()), server.serverId(), false, TransferReason.DRAIN);
            futures.add(transferService.transfer(player, request, null).thenCompose(result -> {
                if (result.successful() || server.type() == ServerType.LOBBY) {
                    return CompletableFuture.completedFuture(result);
                }
                return transferService.transferToType(player, ServerType.LOBBY, TransferReason.DRAIN);
            }));
        }
        logger.info("Migrating {} player(s) away from draining server {}.", futures.size(), server.serverId());
        return futures;
    }

    public void checkServer(String serverName) {
        registry.find(serverName).ifPresent(this::checkServer);
    }

    public void checkAll() {
        registry.servers().forEach(this::checkServer);
    }

    private void checkServer(NetworkServer server) {
        if (!server.draining()) {
            return;
        }
        if (!server.empty()) {
            logger.debug("Server {} is draining with {} local / {} known player(s).", server.serverId(),
                    server.localPlayerCount(), server.knownPlayerCount());
            return;
        }
        registry.setState(server.serverId(), ServerAdminState.OFFLINE, "drain", "drain completed").thenAccept(s -> {
            logger.info("Server {} finished draining and is now OFFLINE / deployment-ready.", s.serverId());
            telemetry.publish(telemetry.event(TelemetryTypes.SERVER_DRAIN_COMPLETED).server(s.serverId()).build());
        });
    }
}
