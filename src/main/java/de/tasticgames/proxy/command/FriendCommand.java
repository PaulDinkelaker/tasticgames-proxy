package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.social.FriendActionResponse;
import de.tasticgames.client.dto.social.FriendRequestResponse;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.social.friend.FriendService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * /friend add|accept|deny|remove|cancel <player> | list | requests
 */
public final class FriendCommand extends CommandSupport {

    private final FriendService friendService;
    private final SocialPlayerLookup lookup;
    private final ProxyServer proxyServer;

    public FriendCommand(FriendService friendService, SocialPlayerLookup lookup, ProxyServer proxyServer,
                         ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.friendService = Objects.requireNonNull(friendService);
        this.lookup = Objects.requireNonNull(lookup);
        this.proxyServer = Objects.requireNonNull(proxyServer);
    }

    @Override
    protected String commandName() {
        return "friend";
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePlayer(source)) {
            return;
        }
        Player player = (Player) source;
        if (!friendService.available()) {
            send(source, "social.unavailable");
            return;
        }
        PlayerRef actor = new PlayerRef(player.getUniqueId(), player.getUsername());
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "list", "l" -> list(source, actor);
            case "requests", "r" -> requests(source, actor);
            case "add", "a" -> withTarget(source, actor, args, (a, t) ->
                    async(source, friendService.sendRequest(a, t, player.hasPermission(ProxyPermissions.SOCIAL_BYPASS_RATELIMIT)),
                            response -> outcome(source, response, t)));
            case "accept" -> withTarget(source, actor, args, (a, t) -> async(source, friendService.accept(a, t), r -> outcome(source, r, t)));
            case "deny" -> withTarget(source, actor, args, (a, t) -> async(source, friendService.deny(a, t), r -> outcome(source, r, t)));
            case "remove" -> withTarget(source, actor, args, (a, t) -> async(source, friendService.remove(a, t), r -> outcome(source, r, t)));
            case "cancel" -> withTarget(source, actor, args, (a, t) -> async(source, friendService.cancel(a, t), r -> outcome(source, r, t)));
            default -> send(source, "friend.usage");
        }
    }

    private void withTarget(CommandSource source, PlayerRef actor, String[] args, BiConsumer<PlayerRef, PlayerRef> action) {
        if (args.length < 2) {
            send(source, "friend.usage");
            return;
        }
        async(source, lookup.byNameOrUuid(args[1]), target -> {
            if (target.isEmpty()) {
                send(source, "common.player_not_found", Map.of("player", args[1]));
                return;
            }
            if (target.get().uuid().equals(actor.uuid())) {
                send(source, "friend.self");
                return;
            }
            action.accept(actor, target.get());
        });
    }

    private void outcome(CommandSource source, FriendActionResponse response, PlayerRef target) {
        Map<String, Object> ph = Map.of("player", target.name());
        switch (response.outcome()) {
            case REQUEST_SENT -> send(source, "friend.request.sent", ph);
            case ACCEPTED, ACCEPTED_CROSSED -> send(source, "friend.accepted", ph);
            case DENIED -> send(source, "friend.denied", ph);
            case REMOVED -> send(source, "friend.removed", ph);
            case CANCELLED -> send(source, "friend.cancelled", ph);
            case ALREADY_FRIENDS -> send(source, "friend.already_friends", ph);
            case ALREADY_REQUESTED -> send(source, "friend.already_requested", ph);
            case NOT_FRIENDS -> send(source, "friend.not_friends", ph);
            case NO_REQUEST -> send(source, "friend.no_request", ph);
            case SELF_TARGET -> send(source, "friend.self");
            case TARGET_UNKNOWN -> send(source, "common.player_not_found", ph);
            case LIMIT_REACHED -> send(source, "friend.limit");
        }
    }

    private void list(CommandSource source, PlayerRef actor) {
        async(source, friendService.overview(actor.uuid()), overview -> {
            send(source, "friend.list.header", Map.of("online", overview.onlineCount(), "total", overview.friends().size()));
            if (overview.friends().isEmpty()) {
                send(source, "friend.list.empty");
            }
            for (FriendService.FriendView view : overview.friends()) {
                String where = view.online() && view.presence().currentServerType() != null
                        ? view.presence().currentServerType().name() : (view.online() ? "online" : "offline");
                send(source, view.online() ? "friend.list.entry_online" : "friend.list.entry_offline",
                        Map.of("player", view.friend().name(), "server", where));
            }
            int incoming = overview.list().incomingRequests().size();
            if (incoming > 0) {
                send(source, "friend.list.pending", Map.of("count", incoming));
            }
        });
    }

    private void requests(CommandSource source, PlayerRef actor) {
        async(source, friendService.list(actor.uuid()), list -> {
            send(source, "friend.requests.header", Map.of("incoming", list.incomingRequests().size(), "outgoing", list.outgoingRequests().size()));
            for (FriendRequestResponse request : list.incomingRequests()) {
                send(source, "friend.requests.incoming", Map.of("player", request.fromName()));
            }
            for (FriendRequestResponse request : list.outgoingRequests()) {
                send(source, "friend.requests.outgoing", Map.of("player", request.toName()));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(List.of("add", "accept", "deny", "remove", "cancel", "list", "requests"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            return filter(proxyServer.getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
        }
        return List.of();
    }
}
