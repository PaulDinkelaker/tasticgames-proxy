package de.tasticgames.proxy.social.party;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.social.PartyResponse;
import de.tasticgames.client.dto.social.PartyTransferCreateRequest;
import de.tasticgames.client.dto.social.PartyTransferStateResponse;
import de.tasticgames.client.dto.social.PartyTransferUpdateRequest;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.routing.CapacityReservationService;
import de.tasticgames.proxy.routing.RoutingDecision;
import de.tasticgames.proxy.routing.RoutingRequest;
import de.tasticgames.proxy.routing.RoutingService;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.routing.TransferResult;
import de.tasticgames.proxy.routing.TransferService;
import de.tasticgames.proxy.routing.TransferStatus;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Atomic-style party transfers: one plan, one target, one capacity reservation, coordinated
 * connects for local members and bus-delegated connects for members on other proxies,
 * central transfer state (API) and partial-failure handling.
 */
public final class PartyTransferService implements ProxyService {

    public record Outcome(UUID transferId, PartyTransferStateResponse state, String target, int succeeded, int failed,
                          List<String> failedPlayers, String message) {
        public boolean success() {
            return state == PartyTransferStateResponse.SUCCESS;
        }
    }

    private final ProxyServer proxyServer;
    private final ProxyApiClient apiClient;
    private final PartyService partyService;
    private final RoutingService routing;
    private final TransferService transferService;
    private final CapacityReservationService reservations;
    private final ServerRegistryService registry;
    private final NetworkCommandBus bus;
    private final NetworkNotificationService notifications;
    private final ProxyConfigurationService configurationService;
    private final TelemetryService telemetry;
    private final ProxyIdentity identity;
    private final Logger logger;

    /** Pending remote member results keyed by transferId:playerUuid. */
    private final Map<String, CompletableFuture<TransferStatus>> remoteResults = new ConcurrentHashMap<>();
    private final Set<UUID> activePartyTransfers = ConcurrentHashMap.newKeySet();

    public PartyTransferService(ProxyServer proxyServer, ProxyApiClient apiClient, PartyService partyService,
                                RoutingService routing, TransferService transferService, CapacityReservationService reservations,
                                ServerRegistryService registry, NetworkCommandBus bus, NetworkNotificationService notifications,
                                ProxyConfigurationService configurationService, TelemetryService telemetry, ProxyIdentity identity,
                                Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer);
        this.apiClient = Objects.requireNonNull(apiClient);
        this.partyService = Objects.requireNonNull(partyService);
        this.routing = Objects.requireNonNull(routing);
        this.transferService = Objects.requireNonNull(transferService);
        this.reservations = Objects.requireNonNull(reservations);
        this.registry = Objects.requireNonNull(registry);
        this.bus = Objects.requireNonNull(bus);
        this.notifications = Objects.requireNonNull(notifications);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.identity = Objects.requireNonNull(identity);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "party-transfer-service";
    }

    @Override
    public void start() {
        bus.subscribe(CommandTypes.PARTY_TRANSFER_MEMBER, this::handleMemberCommand);
        bus.subscribe(CommandTypes.PARTY_TRANSFER_MEMBER_RESULT, this::handleMemberResult);
    }

    @Override
    public void stop() {
        remoteResults.values().forEach(f -> f.complete(TransferStatus.CANCELLED));
        remoteResults.clear();
        activePartyTransfers.clear();
    }

    public int activeTransfers() {
        return activePartyTransfers.size();
    }

    /**
     * Transfers the whole party of {@code leader} to a server of {@code type} (or the given server).
     * The leader must be the party leader; a party is not required (single player fallback).
     */
    public CompletableFuture<Outcome> transferParty(Player leader, ServerType type, String targetServerId, TransferReason reason) {
        UUID leaderUuid = leader.getUniqueId();
        return partyService.overview(leaderUuid).thenCompose(overview -> {
            if (overview.isEmpty()) {
                return transferService.transfer(leader,
                                new RoutingRequest(leaderUuid, type, targetServerId, null, 1, Set.of(), currentServer(leader), false, reason), null)
                        .thenApply(result -> new Outcome(result.transferId(),
                                result.successful() ? PartyTransferStateResponse.SUCCESS : PartyTransferStateResponse.FAILED,
                                result.destination(), result.successful() ? 1 : 0, result.successful() ? 0 : 1,
                                result.successful() ? List.of() : List.of(leader.getUsername()), result.message()));
            }
            PartyService.PartyOverview party = overview.get();
            if (!leaderUuid.equals(party.party().leaderUuid())) {
                return CompletableFuture.completedFuture(new Outcome(null, PartyTransferStateResponse.CANCELLED, "", 0, 0, List.of(), "NOT_LEADER"));
            }
            if (party.party().activeTransfer() != null && !activePartyTransfers.contains(party.party().partyId())
                    && party.party().activeTransfer().expiresAt() != null
                    && party.party().activeTransfer().expiresAt().isAfter(java.time.Instant.now())
                    && (party.party().activeTransfer().state() == PartyTransferStateResponse.PLANNED
                    || party.party().activeTransfer().state() == PartyTransferStateResponse.STARTED)) {
                return CompletableFuture.completedFuture(new Outcome(party.party().activeTransfer().transferId(),
                        PartyTransferStateResponse.CANCELLED, "", 0, 0, List.of(), "TRANSFER_IN_PROGRESS"));
            }
            if (!activePartyTransfers.add(party.party().partyId())) {
                return CompletableFuture.completedFuture(new Outcome(null, PartyTransferStateResponse.CANCELLED, "", 0, 0, List.of(), "TRANSFER_IN_PROGRESS"));
            }
            try {
                return plan(leader, party, type, targetServerId, reason).whenComplete((o, t) -> activePartyTransfers.remove(party.party().partyId()));
            } catch (RuntimeException e) {
                activePartyTransfers.remove(party.party().partyId());
                throw e;
            }
        });
    }

    private CompletableFuture<Outcome> plan(Player leader, PartyService.PartyOverview party, ServerType type,
                                            String targetServerId, TransferReason reason) {
        List<UUID> online = party.onlineMembers();
        if (!online.contains(leader.getUniqueId())) {
            online = new ArrayList<>(online);
            online.add(leader.getUniqueId());
        }
        int groupSize = online.size();
        RoutingRequest request = new RoutingRequest(leader.getUniqueId(), type, targetServerId, null, groupSize, Set.of(),
                null, true, TransferReason.PARTY);
        RoutingDecision decision = routing.route(request);
        if (!decision.found()) {
            return CompletableFuture.completedFuture(new Outcome(null, PartyTransferStateResponse.FAILED, "", 0, groupSize,
                    List.of(), "NO_TARGET: " + decision.summary()));
        }
        NetworkServer target = decision.target();
        UUID reservation = reservations.reserve(target.serverId(), groupSize, target.freeSlots());
        if (reservation == null) {
            return CompletableFuture.completedFuture(new Outcome(null, PartyTransferStateResponse.FAILED, target.serverId(),
                    0, groupSize, List.of(), "TARGET_FULL"));
        }
        UUID transferId = UUID.randomUUID();
        int timeoutSeconds = (int) configurationService.configuration().routing().transferTimeout().toSeconds() * 2 + 5;
        List<UUID> members = online;
        PartyTransferCreateRequest create = new PartyTransferCreateRequest(transferId, leader.getUniqueId(), identity.proxyId(),
                target.serverId(), target.type().name(), members, timeoutSeconds);
        return apiClient.call("party.transfer.create", client -> client.social().createPartyTransfer(party.party().partyId(), create))
                .exceptionallyCompose(throwable -> {
                    reservations.release(reservation);
                    Throwable cause = Throwables.unwrap(throwable);
                    if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 409) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CompletableFuture.failedFuture(cause);
                })
                .thenCompose(created -> {
                    if (created == null) {
                        return CompletableFuture.completedFuture(new Outcome(transferId, PartyTransferStateResponse.CANCELLED,
                                target.serverId(), 0, 0, List.of(), "TRANSFER_IN_PROGRESS"));
                    }
                    return execute(party.party(), transferId, target, members, reason, reservation, timeoutSeconds);
                });
    }

    private CompletableFuture<Outcome> execute(PartyResponse party, UUID transferId, NetworkServer target, List<UUID> members,
                                               TransferReason reason, UUID reservation, int timeoutSeconds) {
        telemetry.publish(telemetry.event(TelemetryTypes.PARTY_TRANSFER_STARTED).server(target.serverId())
                .correlation(transferId.toString()).attribute("party", party.partyId()).attribute("members", members.size()).build());
        logger.info("Party transfer {} -> {} for {} member(s).", TransferService.shortId(transferId), target.serverId(), members.size());
        apiClient.call("party.transfer.started", client -> client.social().updatePartyTransfer(party.partyId(), transferId,
                new PartyTransferUpdateRequest(PartyTransferStateResponse.STARTED, null)));
        partyService.notifyMembers(party, null, "party.transfer.started", Map.of("server", target.type().name()));

        Map<UUID, CompletableFuture<TransferStatus>> futures = new HashMap<>();
        for (UUID member : members) {
            Optional<Player> local = proxyServer.getPlayer(member);
            if (local.isPresent()) {
                RoutingRequest memberRequest = new RoutingRequest(member, target.type(), target.serverId(), null, 1, Set.of(),
                        currentServer(local.get()), false, TransferReason.PARTY);
                futures.put(member, transferService.transfer(local.get(), memberRequest, target).thenApply(TransferResult::status));
            } else {
                CompletableFuture<TransferStatus> remote = new CompletableFuture<>();
                remoteResults.put(transferId + ":" + member, remote);
                Map<String, String> payload = Map.of("transferId", transferId.toString(), "player", member.toString(),
                        "target", target.serverId(), "coordinator", identity.proxyId());
                bus.sendToPlayer(member, CommandTypes.PARTY_TRANSFER_MEMBER, payload).whenComplete((submission, throwable) -> {
                    if (throwable != null || submission.undeliverable()) {
                        remote.complete(TransferStatus.PLAYER_OFFLINE);
                    }
                });
                futures.put(member, remote.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(t -> TransferStatus.TIMEOUT)
                        .whenComplete((s, t) -> remoteResults.remove(transferId + ":" + member)));
            }
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            reservations.release(reservation);
            int succeeded = 0;
            List<String> failed = new ArrayList<>();
            for (Map.Entry<UUID, CompletableFuture<TransferStatus>> entry : futures.entrySet()) {
                TransferStatus status = entry.getValue().join();
                if (status.successful()) {
                    succeeded++;
                } else {
                    failed.add(nameOf(party, entry.getKey()) + " (" + status + ")");
                }
            }
            PartyTransferStateResponse state = failed.isEmpty() ? PartyTransferStateResponse.SUCCESS
                    : succeeded == 0 ? PartyTransferStateResponse.FAILED : PartyTransferStateResponse.PARTIAL_FAILURE;
            String message = failed.isEmpty() ? "" : String.join(", ", failed);
            telemetry.publish(telemetry.event(state == PartyTransferStateResponse.SUCCESS
                    ? TelemetryTypes.PARTY_TRANSFER_COMPLETED : TelemetryTypes.PARTY_TRANSFER_FAILED)
                    .server(target.serverId()).correlation(transferId.toString()).outcome(state)
                    .attribute("succeeded", succeeded).attribute("failed", failed.size()).build());
            if (state != PartyTransferStateResponse.SUCCESS) {
                partyService.notifyMembers(party, null, "party.transfer.partial", Map.of("failed", message));
            }
            int finalSucceeded = succeeded;
            return apiClient.call("party.transfer.finish", client -> client.social().updatePartyTransfer(party.partyId(), transferId,
                            new PartyTransferUpdateRequest(state, message.isBlank() ? null : message)))
                    .handle((r, t) -> new Outcome(transferId, state, target.serverId(), finalSucceeded, failed.size(), failed, message));
        });
    }

    // ------------------------------------------------------------------ bus handlers (member on other proxy)

    private CompletableFuture<String> handleMemberCommand(NetworkCommand command) {
        UUID transferId = UUID.fromString(command.get("transferId"));
        UUID member = UUID.fromString(command.get("player"));
        String targetId = command.get("target");
        String coordinator = command.get("coordinator");
        Optional<Player> player = proxyServer.getPlayer(member);
        if (player.isEmpty()) {
            reply(coordinator, transferId, member, TransferStatus.PLAYER_OFFLINE);
            return CompletableFuture.completedFuture("player not here");
        }
        Optional<NetworkServer> target = registry.find(targetId);
        if (target.isEmpty()) {
            reply(coordinator, transferId, member, TransferStatus.TARGET_UNAVAILABLE);
            return CompletableFuture.completedFuture("unknown target");
        }
        RoutingRequest request = new RoutingRequest(member, target.get().type(), targetId, null, 1, Set.of(),
                currentServer(player.get()), false, TransferReason.PARTY);
        return transferService.transfer(player.get(), request, target.get()).thenApply(result -> {
            reply(coordinator, transferId, member, result.status());
            return result.status().name();
        });
    }

    private void reply(String coordinator, UUID transferId, UUID member, TransferStatus status) {
        Map<String, String> payload = Map.of("transferId", transferId.toString(), "player", member.toString(), "status", status.name());
        bus.sendToProxy(coordinator, CommandTypes.PARTY_TRANSFER_MEMBER_RESULT, payload);
    }

    private CompletableFuture<String> handleMemberResult(NetworkCommand command) {
        String key = command.get("transferId") + ":" + command.get("player");
        CompletableFuture<TransferStatus> pending = remoteResults.get(key);
        if (pending == null) {
            return CompletableFuture.completedFuture("no pending transfer");
        }
        TransferStatus status;
        try {
            status = TransferStatus.valueOf(command.get("status"));
        } catch (RuntimeException e) {
            status = TransferStatus.CONNECTION_FAILED;
        }
        pending.complete(status);
        return CompletableFuture.completedFuture("ok");
    }

    private static String nameOf(PartyResponse party, UUID uuid) {
        return party.members().stream().filter(m -> m.minecraftUuid().equals(uuid)).map(m -> m.name()).findFirst().orElse(uuid.toString());
    }

    private static String currentServer(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName().toLowerCase(java.util.Locale.ROOT)).orElse(null);
    }

    Duration timeout() {
        return configurationService.configuration().routing().transferTimeout();
    }
}
