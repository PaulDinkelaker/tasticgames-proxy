package de.tasticgames.proxy.routing;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.player.NetworkPlayerManager;
import de.tasticgames.proxy.player.NetworkPlayerSession;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Transfer service 1.0: the single owner of {@code createConnectionRequest}. Every transfer is
 * routed, tracked as a {@link TransferOperation}, protected by cooldown, timeout and
 * "already transferring" guards, and reported via telemetry.
 */
public final class TransferService implements ProxyService {

    private final ProxyServer proxyServer;
    private final ServerRegistryService registry;
    private final RoutingService routing;
    private final CapacityReservationService reservations;
    private final NetworkPlayerManager playerManager;
    private final ProxyConfigurationService configurationService;
    private final TelemetryService telemetry;
    private final ProxyMetrics metrics;
    private final ProxyMessages messages;
    private final ProxyScheduler scheduler;
    private final Logger logger;

    private final ConcurrentMap<UUID, TransferOperation> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TransferOperation> recent = new ConcurrentHashMap<>();

    public TransferService(ProxyServer proxyServer, ServerRegistryService registry, RoutingService routing,
                           CapacityReservationService reservations, NetworkPlayerManager playerManager,
                           ProxyConfigurationService configurationService, TelemetryService telemetry,
                           ProxyMetrics metrics, ProxyMessages messages, ProxyScheduler scheduler, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.playerManager = Objects.requireNonNull(playerManager, "playerManager");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "transfer-service";
    }

    @Override
    public void start() {
        scheduler.repeat("transfer-cleanup", Duration.ofSeconds(30), Duration.ofSeconds(30), this::cleanupRecent);
    }

    @Override
    public void stop() {
        active.clear();
        recent.clear();
    }

    // ------------------------------------------------------------------ public API

    /** Transfer to the best server of a type (routing decides). */
    public CompletableFuture<TransferResult> transferToType(Player player, ServerType type, TransferReason reason) {
        return transfer(player, RoutingRequest.forType(player.getUniqueId(), type, currentServerOf(player), reason), null);
    }

    /** Transfer to a concrete server (still validated by routing). */
    public CompletableFuture<TransferResult> transferToServer(Player player, String serverId, TransferReason reason) {
        return transfer(player, RoutingRequest.forServer(player.getUniqueId(), serverId, currentServerOf(player), reason), null);
    }

    /** Full control: routing request + optional pre-decided target (party transfers). */
    public CompletableFuture<TransferResult> transfer(Player player, RoutingRequest request, NetworkServer decidedTarget) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        UUID transferId = UUID.randomUUID();
        UUID uuid = player.getUniqueId();
        String source = currentServerOf(player);
        ProxyConfiguration.Routing config = configurationService.configuration().routing();

        NetworkPlayerSession session = playerManager.find(uuid).orElse(null);
        if (!player.isActive() || session == null) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, request.describeTarget(),
                    TransferStatus.PLAYER_OFFLINE, "player is not online"));
        }
        if (session.pendingTransferId().isPresent()) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, request.describeTarget(),
                    TransferStatus.ALREADY_TRANSFERRING, "a transfer is already in progress"));
        }
        if (request.reason() != TransferReason.FALLBACK && request.reason() != TransferReason.INITIAL_JOIN
                && Duration.between(session.lastTransferAt(), Instant.now()).compareTo(config.transferCooldown()) < 0) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, request.describeTarget(),
                    TransferStatus.COOLDOWN, "transfer cooldown"));
        }

        NetworkServer target = decidedTarget;
        if (target == null) {
            RoutingDecision decision = routing.route(request);
            if (!decision.found()) {
                TransferStatus status = classifyNoTarget(decision);
                return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, request.describeTarget(),
                        status, decision.summary()));
            }
            target = decision.target();
        }
        if (source != null && source.equals(target.serverId())) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, target.serverId(),
                    TransferStatus.ALREADY_CONNECTED, ""));
        }
        if (!target.acceptingPlayers()) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, target.serverId(),
                    TransferStatus.TARGET_UNAVAILABLE, "target is " + target.adminState() + "/" + target.health()));
        }

        TransferOperation operation = new TransferOperation(transferId, uuid, player.getUsername(), source,
                target.serverId(), request.reason(), null);
        if (!playerManager.beginTransfer(uuid, transferId)) {
            return CompletableFuture.completedFuture(finishUntracked(transferId, uuid, target.serverId(),
                    TransferStatus.ALREADY_TRANSFERRING, "a transfer is already in progress"));
        }
        active.put(transferId, operation);
        metrics.gauge("transfers.active", active.size());
        return execute(player, operation, target, config.transferTimeout());
    }

    public Optional<TransferOperation> find(UUID transferId) {
        TransferOperation operation = active.get(transferId);
        return Optional.ofNullable(operation != null ? operation : recent.get(transferId));
    }

    public Collection<TransferOperation> activeTransfers() {
        return List.copyOf(active.values());
    }

    public Collection<TransferOperation> recentTransfers() {
        return List.copyOf(recent.values());
    }

    public Optional<TransferOperation> pendingTransferOf(UUID playerUuid) {
        return active.values().stream().filter(op -> op.playerUuid().equals(playerUuid)).findFirst();
    }

    // ------------------------------------------------------------------ execution

    private CompletableFuture<TransferResult> execute(Player player, TransferOperation operation, NetworkServer target,
                                                      Duration timeout) {
        operation.started();
        metrics.increment("transfers.started");
        telemetry.publish(telemetry.event(TelemetryTypes.TRANSFER_STARTED).player(operation.playerUuid())
                .server(target.serverId()).correlation(operation.correlationId()).outcome(operation.reason())
                .attribute("source", operation.source()).build());
        logger.info("Transfer {} started: {} {} -> {} ({}).", shortId(operation.transferId()), operation.playerName(),
                operation.source().isBlank() ? "-" : operation.source(), target.serverId(), operation.reason());

        CompletableFuture<ConnectionRequestBuilder.Result> connect = player.createConnectionRequest(target.registeredServer()).connect();
        return connect.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, throwable) -> {
                    TransferStatus status;
                    String message;
                    if (throwable != null) {
                        Throwable cause = Throwables.unwrap(throwable);
                        status = cause instanceof java.util.concurrent.TimeoutException ? TransferStatus.TIMEOUT : TransferStatus.CONNECTION_FAILED;
                        message = Throwables.rootMessage(cause);
                    } else {
                        status = mapStatus(result.getStatus());
                        message = result.getReasonComponent()
                                .map(c -> PlainTextComponentSerializer.plainText().serialize(c)).orElse("");
                    }
                    return complete(operation, status, message);
                });
    }

    private TransferResult complete(TransferOperation operation, TransferStatus status, String message) {
        boolean finished = operation.finish(status, message);
        active.remove(operation.transferId());
        recent.put(operation.transferId(), operation);
        playerManager.endTransfer(operation.playerUuid(), operation.transferId());
        metrics.gauge("transfers.active", active.size());
        if (finished) {
            if (status.successful()) {
                metrics.increment("transfers.success");
                telemetry.publish(telemetry.event(TelemetryTypes.TRANSFER_SUCCESS).player(operation.playerUuid())
                        .server(operation.target()).correlation(operation.correlationId()).outcome(status)
                        .duration(operation.durationMillis()).attribute("reason", operation.reason()).build());
                logger.info("Transfer {} finished: {} -> {} ({}, {} ms).", shortId(operation.transferId()),
                        operation.playerName(), operation.target(), status, operation.durationMillis());
            } else {
                metrics.increment("transfers.failed");
                telemetry.publish(telemetry.event(TelemetryTypes.TRANSFER_FAILED).player(operation.playerUuid())
                        .server(operation.target()).correlation(operation.correlationId()).outcome(status)
                        .duration(operation.durationMillis()).attribute("reason", operation.reason())
                        .attribute("message", message).build());
                logger.warn("Transfer {} failed: {} -> {} ({}{}).", shortId(operation.transferId()),
                        operation.playerName(), operation.target(), status, message.isBlank() ? "" : ": " + message);
                notifyFailure(operation, status);
            }
        }
        return operation.result();
    }

    private void notifyFailure(TransferOperation operation, TransferStatus status) {
        if (operation.reason() == TransferReason.FALLBACK || operation.reason() == TransferReason.INITIAL_JOIN) {
            return;
        }
        proxyServer.getPlayer(operation.playerUuid()).ifPresent(player -> messages.send(player, "transfer.failed",
                Map.of("target", operation.target(), "status", status.name())));
    }

    private TransferResult finishUntracked(UUID transferId, UUID player, String target, TransferStatus status, String message) {
        metrics.increment("transfers.rejected");
        telemetry.publish(telemetry.event(TelemetryTypes.TRANSFER_FAILED).player(player).outcome(status)
                .attribute("target", target).attribute("message", message).build());
        return new TransferResult(transferId, status, target, message);
    }

    private static TransferStatus classifyNoTarget(RoutingDecision decision) {
        boolean anyCapacity = decision.excluded().stream().anyMatch(c -> c.exclusionReason().startsWith("capacity"));
        boolean anyType = decision.excluded().stream().anyMatch(c -> !c.exclusionReason().startsWith("type ")
                && !c.exclusionReason().equals("not the requested server"));
        if (anyCapacity && decision.eligible().isEmpty()) {
            return TransferStatus.TARGET_FULL;
        }
        return anyType ? TransferStatus.TARGET_UNAVAILABLE : TransferStatus.NO_TARGET;
    }

    private static TransferStatus mapStatus(ConnectionRequestBuilder.Status status) {
        return switch (status) {
            case SUCCESS -> TransferStatus.SUCCESS;
            case ALREADY_CONNECTED -> TransferStatus.ALREADY_CONNECTED;
            case CONNECTION_IN_PROGRESS -> TransferStatus.ALREADY_TRANSFERRING;
            case CONNECTION_CANCELLED -> TransferStatus.CANCELLED;
            case SERVER_DISCONNECTED -> TransferStatus.CONNECTION_FAILED;
        };
    }

    private String currentServerOf(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName().toLowerCase(java.util.Locale.ROOT)).orElse(null);
    }

    private void cleanupRecent() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(10));
        recent.values().removeIf(op -> op.finishedAt() != null && op.finishedAt().isBefore(threshold));
        // safety net: transfers stuck without completion (should not happen thanks to orTimeout)
        Duration timeout = configurationService.configuration().routing().transferTimeout().multipliedBy(3);
        for (TransferOperation op : active.values()) {
            if (op.createdAt().isBefore(Instant.now().minus(timeout))) {
                logger.warn("Transfer {} for {} timed out without completion – cleaning up.", shortId(op.transferId()), op.playerName());
                complete(op, TransferStatus.TIMEOUT, "watchdog timeout");
            }
        }
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
