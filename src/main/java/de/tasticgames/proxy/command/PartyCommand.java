package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.social.PartyActionResponse;
import de.tasticgames.client.dto.social.PartyInviteResponse;
import de.tasticgames.client.dto.social.PartyMemberResponse;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.social.party.PartyService;
import de.tasticgames.proxy.social.party.PartyTransferService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Ids;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * /party invite|accept|deny|kick|promote <player> | leave | disband | info | chat <msg> | warp <LOBBY|SURVIVAL|...>
 */
public final class PartyCommand extends CommandSupport {

    private final PartyService partyService;
    private final PartyTransferService partyTransferService;
    private final NetworkNotificationService notifications;
    private final SocialPlayerLookup lookup;
    private final ProxyServer proxyServer;
    private final boolean chatOnly;

    public PartyCommand(PartyService partyService, PartyTransferService partyTransferService, NetworkNotificationService notifications,
                        SocialPlayerLookup lookup, ProxyServer proxyServer, ProxyMessages messages, TelemetryService telemetry,
                        Logger logger, boolean chatOnly) {
        super(messages, telemetry, logger);
        this.partyService = Objects.requireNonNull(partyService);
        this.partyTransferService = Objects.requireNonNull(partyTransferService);
        this.notifications = Objects.requireNonNull(notifications);
        this.lookup = Objects.requireNonNull(lookup);
        this.proxyServer = Objects.requireNonNull(proxyServer);
        this.chatOnly = chatOnly;
    }

    @Override
    protected String commandName() {
        return chatOnly ? "pc" : "party";
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePlayer(source)) {
            return;
        }
        Player player = (Player) source;
        if (!partyService.available()) {
            unavailable(source, "API integration disabled - partyService offline");
            return;
        }
        PlayerRef actor = new PlayerRef(player.getUniqueId(), player.getUsername());
        String[] args = invocation.arguments();
        if (chatOnly) {
            chat(source, actor, join(args, 0));
            return;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "info", "list" -> info(source, actor);
            case "invite", "add" -> withTarget(source, actor, args, (a, t) ->
                    async(source, partyService.invite(a, t, player.hasPermission(ProxyPermissions.SOCIAL_BYPASS_RATELIMIT)), r -> outcome(source, r, t)));
            case "accept" -> acceptOrDeny(source, actor, args, true);
            case "deny", "decline" -> acceptOrDeny(source, actor, args, false);
            case "leave" -> async(source, partyService.leave(actor), r -> outcome(source, r, null));
            case "kick" -> withTarget(source, actor, args, (a, t) -> async(source, partyService.kick(a, t), r -> outcome(source, r, t)));
            case "promote" -> withTarget(source, actor, args, (a, t) -> async(source, partyService.promote(a, t), r -> outcome(source, r, t)));
            case "disband" -> async(source, partyService.disband(actor), r -> outcome(source, r, null));
            case "chat", "c" -> chat(source, actor, join(args, 1));
            case "warp", "transfer" -> warp(source, player, args);
            default -> send(source, "party.usage");
        }
    }

    private void withTarget(CommandSource source, PlayerRef actor, String[] args, BiConsumer<PlayerRef, PlayerRef> action) {
        if (args.length < 2) {
            send(source, "party.usage");
            return;
        }
        async(source, lookup.byNameOrUuid(args[1]), target -> {
            if (target.isEmpty()) {
                send(source, "common.player_not_found", Map.of("player", args[1]));
                return;
            }
            if (target.get().uuid().equals(actor.uuid())) {
                send(source, "party.self");
                return;
            }
            action.accept(actor, target.get());
        });
    }

    private void acceptOrDeny(CommandSource source, PlayerRef actor, String[] args, boolean accept) {
        if (args.length >= 2) {
            java.util.Optional<UUID> partyId = Ids.parseUuid(args[1]);
            if (partyId.isPresent()) {
                run(source, actor, null, partyId.get(), accept);
                return;
            }
            async(source, lookup.byNameOrUuid(args[1]), target -> {
                if (target.isEmpty()) {
                    send(source, "common.player_not_found", Map.of("player", args[1]));
                    return;
                }
                run(source, actor, target.get(), null, accept);
            });
            return;
        }
        // no argument: use the newest pending invite
        async(source, partyService.invitesOf(actor.uuid()), invites -> {
            if (invites.isEmpty()) {
                send(source, "party.no_invites");
                return;
            }
            PartyInviteResponse newest = invites.getLast();
            run(source, actor, new PlayerRef(newest.invitedByUuid(), newest.invitedByName()), newest.partyId(), accept);
        });
    }

    private void run(CommandSource source, PlayerRef actor, PlayerRef inviter, UUID partyId, boolean accept) {
        async(source, accept ? partyService.accept(actor, inviter, partyId) : partyService.deny(actor, inviter, partyId),
                r -> outcome(source, r, inviter));
    }

    private void outcome(CommandSource source, PartyActionResponse response, PlayerRef target) {
        Map<String, Object> ph = Map.of("player", target == null ? "" : target.name());
        switch (response.outcome()) {
            case OK, CREATED -> {
                // success feedback depends on the action; the party view is the confirmation
                if (response.party() != null) {
                    send(source, "party.ok", Map.of("size", response.party().size()));
                } else {
                    send(source, "party.ok_left");
                }
            }
            case NOT_IN_PARTY -> send(source, "party.not_in_party");
            case ALREADY_IN_PARTY -> send(source, "party.already_in_party");
            case TARGET_IN_PARTY -> send(source, "party.target_in_party", ph);
            case NOT_LEADER -> send(source, "party.not_leader");
            case PARTY_FULL -> send(source, "party.full");
            case ALREADY_INVITED -> send(source, "party.already_invited", ph);
            case INVITE_NOT_FOUND -> send(source, "party.invite_not_found", ph);
            case INVITE_EXPIRED -> send(source, "party.invite_expired", ph);
            case TARGET_NOT_MEMBER -> send(source, "party.target_not_member", ph);
            case SELF_TARGET -> send(source, "party.self");
            case PARTY_NOT_FOUND -> send(source, "party.not_found");
            case TRANSFER_IN_PROGRESS -> send(source, "party.transfer.in_progress");
            case TARGET_UNKNOWN -> send(source, "common.player_not_found", ph);
            case CONFLICT -> send(source, "social.retry");
        }
    }

    private void info(CommandSource source, PlayerRef actor) {
        async(source, partyService.overview(actor.uuid()), overview -> {
            if (overview.isEmpty()) {
                send(source, "party.not_in_party");
                async(source, partyService.invitesOf(actor.uuid()), invites -> {
                    for (PartyInviteResponse invite : invites) {
                        send(source, "party.info.pending_invite", Map.of("player", invite.invitedByName()));
                    }
                });
                return;
            }
            PartyService.PartyOverview o = overview.get();
            send(source, "party.info.header", Map.of("size", o.party().size(), "online", o.onlineMembers().size()));
            for (PartyMemberResponse member : o.party().members()) {
                boolean online = o.online(member.minecraftUuid());
                String where = online && o.presence().get(member.minecraftUuid()).currentServerType() != null
                        ? o.presence().get(member.minecraftUuid()).currentServerType().name() : (online ? "online" : "offline");
                send(source, member.leader() ? "party.info.leader" : "party.info.member",
                        Map.of("player", member.name(), "server", where, "online", online));
            }
            if (o.party().activeTransfer() != null && !o.party().activeTransfer().state().name().matches("SUCCESS|FAILED|CANCELLED|TIMEOUT|PARTIAL_FAILURE")) {
                send(source, "party.info.transfer", Map.of("state", o.party().activeTransfer().state(),
                        "target", String.valueOf(o.party().activeTransfer().targetServerId())));
            }
        });
    }

    private void chat(CommandSource source, PlayerRef actor, String message) {
        if (message.isBlank()) {
            send(source, "party.chat.usage");
            return;
        }
        async(source, partyService.partyOf(actor.uuid()), party -> {
            if (party.isEmpty()) {
                send(source, "party.not_in_party");
                return;
            }
            partyService.notifyMembers(party.get(), null, "party.chat.format", Map.of("player", actor.name(), "message", message));
        });
    }

    private void warp(CommandSource source, Player player, String[] args) {
        if (args.length < 2) {
            send(source, "party.usage");
            return;
        }
        ServerType type = ServerType.find(args[1]).orElse(null);
        String serverId = type == null && Ids.isServerId(args[1]) ? args[1].toLowerCase(java.util.Locale.ROOT) : null;
        if (type == null && serverId == null) {
            send(source, "party.usage");
            return;
        }
        adminTelemetryIfAdmin(source);
        async(source, partyTransferService.transferParty(player, type, serverId, TransferReason.PARTY), outcome -> {
            switch (outcome.state()) {
                case SUCCESS -> send(source, "party.transfer.success", Map.of("count", outcome.succeeded(), "target", outcome.target()));
                case PARTIAL_FAILURE -> send(source, "party.transfer.partial", Map.of("failed", String.join(", ", outcome.failedPlayers())));
                case CANCELLED -> {
                    if ("NOT_LEADER".equals(outcome.message())) {
                        send(source, "party.not_leader");
                    } else {
                        send(source, "party.transfer.in_progress");
                    }
                }
                default -> send(source, "party.transfer.failed", Map.of("reason", outcome.message()));
            }
        });
    }

    private void adminTelemetryIfAdmin(CommandSource source) {
        // party warp is a normal player action; no admin telemetry
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (chatOnly) {
            return List.of();
        }
        if (args.length <= 1) {
            return filter(List.of("invite", "accept", "deny", "leave", "kick", "promote", "disband", "info", "chat", "warp"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("warp")) {
            return filter(List.of("LOBBY", "SURVIVAL", "CREATIVE", "DUELS"), args[1]);
        }
        if (args.length == 2) {
            return filter(proxyServer.getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
        }
        return List.of();
    }
}
