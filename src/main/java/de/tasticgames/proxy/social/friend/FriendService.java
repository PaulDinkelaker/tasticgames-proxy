package de.tasticgames.proxy.social.friend;

import de.tasticgames.client.dto.network.NetworkPresenceResponse;
import de.tasticgames.client.dto.social.FriendActionRequest;
import de.tasticgames.client.dto.social.FriendActionResponse;
import de.tasticgames.client.dto.social.FriendListResponse;
import de.tasticgames.client.dto.social.FriendOutcomeResponse;
import de.tasticgames.client.dto.social.FriendResponse;
import de.tasticgames.client.dto.social.PlayerRefResponse;
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
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Friends 1.0: central persistence via API, notifications across proxies, presence-aware
 * listing, join/quit notifications, local rate limiting.
 */
public final class FriendService implements ProxyService {

    public record FriendView(FriendResponse friend, NetworkPresenceResponse presence) {
        public boolean online() {
            return presence != null && presence.online();
        }
    }

    public record FriendOverview(FriendListResponse list, List<FriendView> friends) {
        public long onlineCount() {
            return friends.stream().filter(FriendView::online).count();
        }
    }

    private final ProxyApiClient apiClient;
    private final ProxyConfigurationService configurationService;
    private final NetworkPresenceService presenceService;
    private final NetworkNotificationService notifications;
    private final TelemetryService telemetry;
    private final RateLimiter rateLimiter;
    private final Logger logger;

    public FriendService(ProxyApiClient apiClient, ProxyConfigurationService configurationService,
                         NetworkPresenceService presenceService, NetworkNotificationService notifications,
                         TelemetryService telemetry, RateLimiter rateLimiter, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.presenceService = Objects.requireNonNull(presenceService, "presenceService");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "friend-service";
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

    public CompletableFuture<FriendOverview> overview(UUID player) {
        return apiClient.call("friend.list", client -> client.social().friends(player)).thenCompose(list -> {
            List<UUID> uuids = list.friends().stream().map(FriendResponse::minecraftUuid).toList();
            return presenceService.lookupAll(uuids).exceptionally(t -> List.of()).thenApply(presences -> {
                Map<UUID, NetworkPresenceResponse> byUuid = new HashMap<>();
                for (NetworkPresenceResponse presence : presences) {
                    byUuid.put(presence.minecraftUuid(), presence);
                }
                List<FriendView> views = list.friends().stream()
                        .map(f -> new FriendView(f, byUuid.get(f.minecraftUuid())))
                        .sorted((a, b) -> {
                            if (a.online() != b.online()) {
                                return a.online() ? -1 : 1;
                            }
                            return a.friend().name().compareToIgnoreCase(b.friend().name());
                        }).toList();
                return new FriendOverview(list, views);
            });
        });
    }

    public CompletableFuture<FriendListResponse> list(UUID player) {
        return apiClient.call("friend.list", client -> client.social().friends(player));
    }

    public CompletableFuture<FriendActionResponse> sendRequest(PlayerRef actor, PlayerRef target, boolean bypassRateLimit) {
        ProxyConfiguration.Social social = configurationService.configuration().social();
        if (!bypassRateLimit && !rateLimiter.tryAcquire(actor.uuid(), "friend.request", social.inviteRateLimit(), social.inviteRateWindow())) {
            return CompletableFuture.failedFuture(new RateLimitedException());
        }
        if (!bypassRateLimit && !rateLimiter.tryCooldown(actor.uuid(), "friend.request." + target.uuid(), social.friendRequestCooldown())) {
            return CompletableFuture.failedFuture(new RateLimitedException());
        }
        return apiClient.call("friend.request", client -> client.social().sendFriendRequest(actor.uuid(),
                        new FriendActionRequest(target.uuid(), UUID.randomUUID())))
                .thenApply(response -> {
                    switch (response.outcome()) {
                        case REQUEST_SENT -> {
                            telemetry.publish(telemetry.event(TelemetryTypes.FRIEND_REQUESTED).player(actor.uuid()).attribute("target", target.uuid()).build());
                            notifications.notify(target.uuid(), "friend.request.received", Map.of("player", actor.name()));
                        }
                        case ACCEPTED_CROSSED -> {
                            telemetry.publish(telemetry.event(TelemetryTypes.FRIEND_ACCEPTED).player(actor.uuid()).attribute("target", target.uuid()).build());
                            notifications.notify(target.uuid(), "friend.accepted.notify", Map.of("player", actor.name()));
                        }
                        default -> { }
                    }
                    return response;
                });
    }

    public CompletableFuture<FriendActionResponse> accept(PlayerRef actor, PlayerRef from) {
        return apiClient.call("friend.accept", client -> client.social().acceptFriendRequest(actor.uuid(), from.uuid()))
                .thenApply(response -> {
                    if (response.outcome() == FriendOutcomeResponse.ACCEPTED) {
                        telemetry.publish(telemetry.event(TelemetryTypes.FRIEND_ACCEPTED).player(actor.uuid()).attribute("target", from.uuid()).build());
                        notifications.notify(from.uuid(), "friend.accepted.notify", Map.of("player", actor.name()));
                    }
                    return response;
                });
    }

    public CompletableFuture<FriendActionResponse> deny(PlayerRef actor, PlayerRef from) {
        return apiClient.call("friend.deny", client -> client.social().denyFriendRequest(actor.uuid(), from.uuid()))
                .thenApply(response -> {
                    if (response.outcome() == FriendOutcomeResponse.DENIED) {
                        telemetry.publish(telemetry.event(TelemetryTypes.FRIEND_DENIED).player(actor.uuid()).attribute("target", from.uuid()).build());
                    }
                    return response;
                });
    }

    public CompletableFuture<FriendActionResponse> remove(PlayerRef actor, PlayerRef other) {
        return apiClient.call("friend.remove", client -> client.social().removeFriend(actor.uuid(), other.uuid()))
                .thenApply(response -> {
                    if (response.outcome() == FriendOutcomeResponse.REMOVED) {
                        telemetry.publish(telemetry.event(TelemetryTypes.FRIEND_REMOVED).player(actor.uuid()).attribute("target", other.uuid()).build());
                        notifications.notify(other.uuid(), "friend.removed.notify", Map.of("player", actor.name()));
                    }
                    return response;
                });
    }

    public CompletableFuture<FriendActionResponse> cancel(PlayerRef actor, PlayerRef target) {
        return apiClient.call("friend.cancel", client -> client.social().cancelFriendRequest(actor.uuid(), target.uuid()));
    }

    /** Join/quit notifications to friends (network-wide, presence based). */
    public void notifyPresenceChange(NetworkPlayerSession session, boolean joined) {
        if (!available() || !configurationService.configuration().social().friendJoinNotifications()) {
            return;
        }
        apiClient.call("friend.list", client -> client.social().friends(session.minecraftUuid()))
                .whenComplete((list, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Friend presence notification skipped for {}: {}", session.username(), Throwables.rootMessage(throwable));
                        return;
                    }
                    if (list.friends().isEmpty()) {
                        return;
                    }
                    List<UUID> friends = list.friends().stream().map(FriendResponse::minecraftUuid).toList();
                    presenceService.lookupAll(friends).whenComplete((presences, t) -> {
                        if (t != null) {
                            return;
                        }
                        for (NetworkPresenceResponse presence : presences) {
                            if (presence.online()) {
                                notifications.notify(presence.minecraftUuid(), joined ? "friend.online" : "friend.offline",
                                        Map.of("player", session.username()));
                            }
                        }
                    });
                });
    }

    public static PlayerRef ref(PlayerRefResponse response) {
        return response == null ? null : new PlayerRef(response.minecraftUuid(), response.name());
    }

    /** Thrown when a local rate limit blocks the action. */
    public static final class RateLimitedException extends RuntimeException {
        public RateLimitedException() {
            super("rate limited");
        }
    }
}
