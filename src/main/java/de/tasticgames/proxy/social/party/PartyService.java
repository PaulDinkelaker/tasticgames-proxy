package de.tasticgames.proxy.social.party;

import de.tasticgames.client.dto.network.NetworkPresenceResponse;
import de.tasticgames.client.dto.social.PartyActionRequest;
import de.tasticgames.client.dto.social.PartyActionResponse;
import de.tasticgames.client.dto.social.PartyInviteResponse;
import de.tasticgames.client.dto.social.PartyMemberResponse;
import de.tasticgames.client.dto.social.PartyOutcomeResponse;
import de.tasticgames.client.dto.social.PartyResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.player.NetworkPlayerSession;
import de.tasticgames.proxy.presence.NetworkPresenceService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.RateLimiter;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Party 1.0: central persistence via API (one party per player enforced server side),
 * notifications to all members across proxies, deterministic leader handover when the
 * leader stays offline, invite rate limiting.
 */
public final class PartyService implements ProxyService {

    private static final Duration LEADER_OFFLINE_GRACE = Duration.ofSeconds(60);

    public record PartyOverview(PartyResponse party, Map<UUID, NetworkPresenceResponse> presence) {
        public boolean online(UUID member) {
            NetworkPresenceResponse p = presence.get(member);
            return p != null && p.online();
        }

        public List<UUID> onlineMembers() {
            return party.members().stream().map(PartyMemberResponse::minecraftUuid).filter(this::online).toList();
        }
    }

    private final ProxyApiClient apiClient;
    private final ProxyConfigurationService configurationService;
    private final NetworkPresenceService presenceService;
    private final NetworkNotificationService notifications;
    private final TelemetryService telemetry;
    private final RateLimiter rateLimiter;
    private final ProxyScheduler scheduler;
    private final Logger logger;

    public PartyService(ProxyApiClient apiClient, ProxyConfigurationService configurationService,
                        NetworkPresenceService presenceService, NetworkNotificationService notifications,
                        TelemetryService telemetry, RateLimiter rateLimiter, ProxyScheduler scheduler, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.presenceService = Objects.requireNonNull(presenceService, "presenceService");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "party-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public boolean available() {
        return apiClient.enabled();
    }

    public CompletableFuture<Optional<PartyResponse>> partyOf(UUID player) {
        return apiClient.call("party.of", client -> client.social().partyOf(player));
    }

    public CompletableFuture<Optional<PartyOverview>> overview(UUID player) {
        return partyOf(player).thenCompose(party -> {
            if (party.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            List<UUID> members = party.get().members().stream().map(PartyMemberResponse::minecraftUuid).toList();
            return presenceService.lookupAll(members).exceptionally(t -> List.of()).thenApply(presences -> {
                Map<UUID, NetworkPresenceResponse> byUuid = new HashMap<>();
                presences.forEach(p -> byUuid.put(p.minecraftUuid(), p));
                return Optional.of(new PartyOverview(party.get(), byUuid));
            });
        });
    }

    public CompletableFuture<List<PartyInviteResponse>> invitesOf(UUID player) {
        return apiClient.call("party.invites", client -> client.social().partyInvitesOf(player));
    }

    public CompletableFuture<PartyActionResponse> invite(PlayerRef actor, PlayerRef target, boolean bypassRateLimit) {
        ProxyConfiguration.Social social = configurationService.configuration().social();
        if (!bypassRateLimit && !rateLimiter.tryAcquire(actor.uuid(), "party.invite", social.inviteRateLimit(), social.inviteRateWindow())) {
            return CompletableFuture.failedFuture(new de.tasticgames.proxy.social.friend.FriendService.RateLimitedException());
        }
        PartyActionRequest request = new PartyActionRequest(actor.uuid(), target.uuid(), null, UUID.randomUUID(),
                social.partyMaxSize(), (int) social.partyInviteTimeout().toSeconds());
        return apiClient.call("party.invite", client -> client.social().partyInvite(request)).thenApply(response -> {
            if (response.ok()) {
                if (response.outcome() == PartyOutcomeResponse.CREATED) {
                    telemetry.publish(telemetry.event(TelemetryTypes.PARTY_CREATED).player(actor.uuid()).build());
                }
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_INVITED).player(actor.uuid()).attribute("target", target.uuid()).build());
                notifications.notify(target.uuid(), "party.invite.received", Map.of("player", actor.name(),
                        "seconds", social.partyInviteTimeout().toSeconds()));
                notifyMembers(response.party(), actor.uuid(), "party.invite.sent_broadcast",
                        Map.of("player", actor.name(), "target", target.name()));
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> accept(PlayerRef actor, PlayerRef inviter, UUID partyId) {
        PartyActionRequest request = new PartyActionRequest(actor.uuid(), inviter == null ? null : inviter.uuid(), partyId,
                UUID.randomUUID(), configurationService.configuration().social().partyMaxSize(), null);
        return apiClient.call("party.accept", client -> client.social().partyAccept(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_JOINED).player(actor.uuid()).build());
                notifyMembers(response.party(), actor.uuid(), "party.joined.broadcast", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> deny(PlayerRef actor, PlayerRef inviter, UUID partyId) {
        PartyActionRequest request = new PartyActionRequest(actor.uuid(), inviter == null ? null : inviter.uuid(), partyId,
                UUID.randomUUID(), null, null);
        return apiClient.call("party.deny", client -> client.social().partyDeny(request)).thenApply(response -> {
            if (response.ok() && inviter != null) {
                notifications.notify(inviter.uuid(), "party.invite.denied_notify", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> leave(PlayerRef actor) {
        return apiClient.call("party.leave", client -> client.social().partyLeave(simple(actor.uuid()))).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_LEFT).player(actor.uuid()).build());
                notifyMembers(response.party(), actor.uuid(), "party.left.broadcast", Map.of("player", actor.name()));
                announceLeaderIfChanged(response.party(), actor.uuid());
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> kick(PlayerRef actor, PlayerRef target) {
        PartyActionRequest request = new PartyActionRequest(actor.uuid(), target.uuid(), null, UUID.randomUUID(), null, null);
        return apiClient.call("party.kick", client -> client.social().partyKick(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_KICKED).player(actor.uuid()).attribute("target", target.uuid()).build());
                notifications.notify(target.uuid(), "party.kicked.notify", Map.of("player", actor.name()));
                notifyMembers(response.party(), actor.uuid(), "party.kicked.broadcast", Map.of("player", actor.name(), "target", target.name()));
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> promote(PlayerRef actor, PlayerRef target) {
        PartyActionRequest request = new PartyActionRequest(actor.uuid(), target.uuid(), null, UUID.randomUUID(), null, null);
        return apiClient.call("party.promote", client -> client.social().partyPromote(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_PROMOTED).player(actor.uuid()).attribute("target", target.uuid()).build());
                notifyMembers(response.party(), null, "party.promoted.broadcast", Map.of("player", target.name()));
            }
            return response;
        });
    }

    public CompletableFuture<PartyActionResponse> disband(PlayerRef actor) {
        return apiClient.call("party.disband", client -> client.social().partyDisband(simple(actor.uuid()))).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.PARTY_DISBANDED).player(actor.uuid()).build());
                notifyMembers(response.party(), null, "party.disbanded.broadcast", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    /** Called on disconnect: records offline state and schedules leader handover if needed. */
    public void memberOffline(NetworkPlayerSession session) {
        if (!available()) {
            return;
        }
        UUID uuid = session.minecraftUuid();
        apiClient.call("party.member-offline", client -> client.social().partyMemberOffline(simple(uuid)))
                .whenComplete((response, throwable) -> {
                    if (throwable != null || response == null || !response.ok() || response.party() == null) {
                        return;
                    }
                    PartyResponse party = response.party();
                    if (!uuid.equals(party.leaderUuid()) || party.members().size() < 2) {
                        return;
                    }
                    scheduler.later("party-leader-handover", LEADER_OFFLINE_GRACE, () -> handoverIfStillOffline(party.partyId(), uuid));
                });
    }

    private void handoverIfStillOffline(UUID partyId, UUID leader) {
        presenceService.lookup(leader).whenComplete((presence, throwable) -> {
            if (throwable != null || (presence.isPresent() && presence.get().online())) {
                return;
            }
            apiClient.call("party.get", client -> client.social().party(partyId)).whenComplete((party, t) -> {
                if (t != null || party.isEmpty() || !leader.equals(party.get().leaderUuid())) {
                    return;
                }
                Optional<PartyMemberResponse> next = party.get().members().stream()
                        .filter(m -> !m.minecraftUuid().equals(leader))
                        .min(java.util.Comparator.comparing(PartyMemberResponse::joinedAt));
                if (next.isEmpty()) {
                    return;
                }
                PartyActionRequest request = new PartyActionRequest(leader, next.get().minecraftUuid(), partyId, UUID.randomUUID(), null, null);
                apiClient.call("party.promote", client -> client.social().partyPromote(request)).whenComplete((response, err) -> {
                    if (err == null && response.ok()) {
                        logger.info("Party {}: leader {} stayed offline, promoted {}.", partyId, leader, next.get().name());
                        notifyMembers(response.party(), null, "party.promoted.broadcast", Map.of("player", next.get().name()));
                    }
                });
            });
        });
    }

    private void announceLeaderIfChanged(PartyResponse party, UUID leaver) {
        if (party == null || party.state() != de.tasticgames.client.dto.social.PartyStateResponse.ACTIVE) {
            return;
        }
        // when the leaver was the leader the API promoted the earliest member; announce it
        boolean leaverWasLeader = party.members().stream().noneMatch(m -> m.minecraftUuid().equals(leaver));
        if (leaverWasLeader && party.leaderUuid() != null) {
            party.members().stream().filter(m -> m.minecraftUuid().equals(party.leaderUuid())).findFirst()
                    .ifPresent(leader -> notifyMembers(party, null, "party.promoted.broadcast", Map.of("player", leader.name())));
        }
    }

    public void notifyMembers(PartyResponse party, UUID except, String key, Map<String, ?> placeholders) {
        if (party == null) {
            return;
        }
        for (PartyMemberResponse member : party.members()) {
            if (except != null && except.equals(member.minecraftUuid())) {
                continue;
            }
            notifications.notify(member.minecraftUuid(), key, placeholders);
        }
    }

    private static PartyActionRequest simple(UUID actor) {
        return new PartyActionRequest(actor, null, null, UUID.randomUUID(), null, null);
    }

    static String describe(Throwable throwable) {
        return Throwables.rootMessage(throwable);
    }
}
