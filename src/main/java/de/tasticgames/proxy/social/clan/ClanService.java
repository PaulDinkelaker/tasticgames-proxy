package de.tasticgames.proxy.social.clan;

import de.tasticgames.client.dto.network.NetworkPresenceResponse;
import de.tasticgames.client.dto.social.ClanActionRequest;
import de.tasticgames.client.dto.social.ClanActionResponse;
import de.tasticgames.client.dto.social.ClanInviteResponse;
import de.tasticgames.client.dto.social.ClanMemberResponse;
import de.tasticgames.client.dto.social.ClanResponse;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.presence.NetworkPresenceService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.RateLimiter;
import de.tasticgames.proxy.social.friend.FriendService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.Ids;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Clan alpha service: central persistence via API, notifications, telemetry.
 */
public final class ClanService implements ProxyService {

    public record ClanOverview(ClanResponse clan, Map<UUID, NetworkPresenceResponse> presence) {
        public boolean online(UUID member) {
            NetworkPresenceResponse p = presence.get(member);
            return p != null && p.online();
        }
        public long onlineCount() {
            return clan.members().stream().filter(m -> online(m.minecraftUuid())).count();
        }
    }

    private final ProxyApiClient apiClient;
    private final ProxyConfigurationService configurationService;
    private final NetworkPresenceService presenceService;
    private final NetworkNotificationService notifications;
    private final TelemetryService telemetry;
    private final RateLimiter rateLimiter;
    private final Logger logger;

    public ClanService(ProxyApiClient apiClient, ProxyConfigurationService configurationService,
                       NetworkPresenceService presenceService, NetworkNotificationService notifications,
                       TelemetryService telemetry, RateLimiter rateLimiter, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.presenceService = Objects.requireNonNull(presenceService);
        this.notifications = Objects.requireNonNull(notifications);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "clan-service";
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

    public CompletableFuture<Optional<ClanResponse>> clanOf(UUID player) {
        return apiClient.call("clan.of", client -> client.social().clanOf(player));
    }

    public CompletableFuture<Optional<ClanResponse>> clanByName(String name) {
        if (!Ids.isClanName(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return apiClient.call("clan.byName", client -> client.social().clanByName(name));
    }

    public CompletableFuture<Optional<ClanOverview>> overview(UUID player) {
        return clanOf(player).thenCompose(clan -> {
            if (clan.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            List<UUID> members = clan.get().members().stream().map(ClanMemberResponse::minecraftUuid).toList();
            return presenceService.lookupAll(members).exceptionally(t -> List.of()).thenApply(presences -> {
                Map<UUID, NetworkPresenceResponse> byUuid = new HashMap<>();
                presences.forEach(p -> byUuid.put(p.minecraftUuid(), p));
                return Optional.of(new ClanOverview(clan.get(), byUuid));
            });
        });
    }

    public CompletableFuture<List<ClanInviteResponse>> invitesOf(UUID player) {
        return apiClient.call("clan.invites", client -> client.social().clanInvitesOf(player));
    }

    public CompletableFuture<ClanActionResponse> create(PlayerRef actor, String name) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, null, name, UUID.randomUUID(), limit(), null);
        return apiClient.call("clan.create", client -> client.social().clanCreate(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_CREATED).player(actor.uuid()).attribute("clan", name).build());
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> invite(PlayerRef actor, PlayerRef target, boolean bypassRateLimit) {
        ProxyConfiguration.Social social = configurationService.configuration().social();
        if (!bypassRateLimit && !rateLimiter.tryAcquire(actor.uuid(), "clan.invite", social.inviteRateLimit(), social.inviteRateWindow())) {
            return CompletableFuture.failedFuture(new FriendService.RateLimitedException());
        }
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), limit(),
                (int) social.clanInviteTimeout().toSeconds());
        return apiClient.call("clan.invite", client -> client.social().clanInvite(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_INVITED).player(actor.uuid()).attribute("target", target.uuid()).build());
                notifications.notify(target.uuid(), "clan.invite.received", Map.of("player", actor.name(),
                        "clan", response.clan() == null ? "?" : response.clan().name()));
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> acceptInvite(PlayerRef actor, UUID clanId) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, clanId, null, UUID.randomUUID(), limit(), null);
        return apiClient.call("clan.accept-invite", client -> client.social().clanAcceptInvite(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_JOINED).player(actor.uuid()).build());
                notifyMembers(response.clan(), actor.uuid(), "clan.joined.broadcast", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> denyInvite(PlayerRef actor, UUID clanId) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, clanId, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.deny-invite", client -> client.social().clanDenyInvite(request));
    }

    public CompletableFuture<ClanActionResponse> requestJoin(PlayerRef actor, UUID clanId) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, clanId, null, UUID.randomUUID(), limit(), null);
        return apiClient.call("clan.request", client -> client.social().clanRequestJoin(request)).thenApply(response -> {
            if (response.ok() && response.clan() != null) {
                for (ClanMemberResponse member : response.clan().members()) {
                    if (member.role() != de.tasticgames.client.dto.social.ClanRoleResponse.MEMBER) {
                        notifications.notify(member.minecraftUuid(), "clan.request.received", Map.of("player", actor.name()));
                    }
                }
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> acceptRequest(PlayerRef actor, PlayerRef target) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), limit(), null);
        return apiClient.call("clan.accept-request", client -> client.social().clanAcceptRequest(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_JOINED).player(target.uuid()).build());
                notifications.notify(target.uuid(), "clan.request.accepted_notify", Map.of("clan", response.clan() == null ? "?" : response.clan().name()));
                notifyMembers(response.clan(), target.uuid(), "clan.joined.broadcast", Map.of("player", target.name()));
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> denyRequest(PlayerRef actor, PlayerRef target) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.deny-request", client -> client.social().clanDenyRequest(request));
    }

    public CompletableFuture<ClanActionResponse> kick(PlayerRef actor, PlayerRef target) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.kick", client -> client.social().clanKick(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_LEFT).player(target.uuid()).outcome("KICKED").build());
                notifications.notify(target.uuid(), "clan.kicked.notify", Map.of("player", actor.name()));
                notifyMembers(response.clan(), null, "clan.kicked.broadcast", Map.of("player", actor.name(), "target", target.name()));
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> promote(PlayerRef actor, PlayerRef target) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.promote", client -> client.social().clanPromote(request)).thenApply(response -> roleChanged(response, actor, target));
    }

    public CompletableFuture<ClanActionResponse> demote(PlayerRef actor, PlayerRef target) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), target.uuid(), null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.demote", client -> client.social().clanDemote(request)).thenApply(response -> roleChanged(response, actor, target));
    }

    private ClanActionResponse roleChanged(ClanActionResponse response, PlayerRef actor, PlayerRef target) {
        if (response.ok() && response.clan() != null) {
            String role = response.clan().members().stream().filter(m -> m.minecraftUuid().equals(target.uuid()))
                    .map(m -> m.role().name()).findFirst().orElse("?");
            telemetry.publish(telemetry.event(TelemetryTypes.CLAN_ROLE_CHANGED).player(actor.uuid())
                    .attribute("target", target.uuid()).attribute("role", role).build());
            notifyMembers(response.clan(), null, "clan.role.changed_broadcast", Map.of("player", target.name(), "role", role));
        }
        return response;
    }

    public CompletableFuture<ClanActionResponse> leave(PlayerRef actor) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.leave", client -> client.social().clanLeave(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_LEFT).player(actor.uuid()).build());
                notifyMembers(response.clan(), actor.uuid(), "clan.left.broadcast", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    public CompletableFuture<ClanActionResponse> disband(PlayerRef actor) {
        ClanActionRequest request = new ClanActionRequest(actor.uuid(), null, null, null, UUID.randomUUID(), null, null);
        return apiClient.call("clan.disband", client -> client.social().clanDisband(request)).thenApply(response -> {
            if (response.ok()) {
                telemetry.publish(telemetry.event(TelemetryTypes.CLAN_DISBANDED).player(actor.uuid()).build());
                notifyMembers(response.clan(), actor.uuid(), "clan.disbanded.broadcast", Map.of("player", actor.name()));
            }
            return response;
        });
    }

    private void notifyMembers(ClanResponse clan, UUID except, String key, Map<String, ?> placeholders) {
        if (clan == null) {
            return;
        }
        for (ClanMemberResponse member : clan.members()) {
            if (except != null && except.equals(member.minecraftUuid())) {
                continue;
            }
            notifications.notify(member.minecraftUuid(), key, placeholders);
        }
    }

    private int limit() {
        return configurationService.configuration().social().clanMemberLimit();
    }
}
