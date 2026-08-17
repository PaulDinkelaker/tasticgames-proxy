package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.social.ClanActionResponse;
import de.tasticgames.client.dto.social.ClanInviteResponse;
import de.tasticgames.client.dto.social.ClanJoinRequestResponse;
import de.tasticgames.client.dto.social.ClanMemberResponse;
import de.tasticgames.client.dto.social.ClanResponse;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.social.clan.ClanService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Ids;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * /clan create <name> | info [clan] | invite|kick|promote|demote <player> | accept|deny [clan] | request <clan>
 * | requests | acceptrequest|denyrequest <player> | leave | disband
 */
public final class ClanCommand extends CommandSupport {

    private final ClanService clanService;
    private final SocialPlayerLookup lookup;
    private final ProxyServer proxyServer;

    public ClanCommand(ClanService clanService, SocialPlayerLookup lookup, ProxyServer proxyServer,
                       ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.clanService = Objects.requireNonNull(clanService);
        this.lookup = Objects.requireNonNull(lookup);
        this.proxyServer = Objects.requireNonNull(proxyServer);
    }

    @Override
    protected String commandName() {
        return "clan";
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePlayer(source)) {
            return;
        }
        Player player = (Player) source;
        if (!clanService.available()) {
            unavailable(source, "API integration disabled - clanService offline");
            return;
        }
        PlayerRef actor = new PlayerRef(player.getUniqueId(), player.getUsername());
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "info" -> info(source, actor, args.length >= 2 ? args[1] : null);
            case "create" -> {
                if (args.length < 2 || !Ids.isClanName(args[1])) {
                    send(source, "clan.invalid_name");
                    return;
                }
                async(source, clanService.create(actor, args[1]), r -> outcome(source, r, null));
            }
            case "invite" -> withTarget(source, actor, args, (a, t) ->
                    async(source, clanService.invite(a, t, player.hasPermission(ProxyPermissions.SOCIAL_BYPASS_RATELIMIT)), r -> outcome(source, r, t)));
            case "kick" -> withTarget(source, actor, args, (a, t) -> async(source, clanService.kick(a, t), r -> outcome(source, r, t)));
            case "promote" -> withTarget(source, actor, args, (a, t) -> async(source, clanService.promote(a, t), r -> outcome(source, r, t)));
            case "demote" -> withTarget(source, actor, args, (a, t) -> async(source, clanService.demote(a, t), r -> outcome(source, r, t)));
            case "accept" -> withInvite(source, actor, args, clanId -> async(source, clanService.acceptInvite(actor, clanId), r -> outcome(source, r, null)));
            case "deny", "decline" -> withInvite(source, actor, args, clanId -> async(source, clanService.denyInvite(actor, clanId), r -> outcome(source, r, null)));
            case "request", "join" -> {
                if (args.length < 2) {
                    send(source, "clan.usage");
                    return;
                }
                async(source, clanService.clanByName(args[1]), clan -> {
                    if (clan.isEmpty()) {
                        send(source, "clan.not_found", Map.of("clan", args[1]));
                        return;
                    }
                    async(source, clanService.requestJoin(actor, clan.get().clanId()), r -> outcome(source, r, null));
                });
            }
            case "requests" -> async(source, clanService.clanOf(actor.uuid()), clan -> {
                if (clan.isEmpty()) {
                    send(source, "clan.not_in_clan");
                    return;
                }
                send(source, "clan.requests.header", Map.of("count", clan.get().joinRequests().size()));
                for (ClanJoinRequestResponse request : clan.get().joinRequests()) {
                    send(source, "clan.requests.entry", Map.of("player", request.playerName()));
                }
            });
            case "acceptrequest" -> withTarget(source, actor, args, (a, t) -> async(source, clanService.acceptRequest(a, t), r -> outcome(source, r, t)));
            case "denyrequest" -> withTarget(source, actor, args, (a, t) -> async(source, clanService.denyRequest(a, t), r -> outcome(source, r, t)));
            case "leave" -> async(source, clanService.leave(actor), r -> outcome(source, r, null));
            case "disband" -> async(source, clanService.disband(actor), r -> outcome(source, r, null));
            default -> send(source, "clan.usage");
        }
    }

    private void withTarget(CommandSource source, PlayerRef actor, String[] args, BiConsumer<PlayerRef, PlayerRef> action) {
        if (args.length < 2) {
            send(source, "clan.usage");
            return;
        }
        async(source, lookup.byNameOrUuid(args[1]), target -> {
            if (target.isEmpty()) {
                send(source, "common.player_not_found", Map.of("player", args[1]));
                return;
            }
            if (target.get().uuid().equals(actor.uuid())) {
                send(source, "clan.self");
                return;
            }
            action.accept(actor, target.get());
        });
    }

    private void withInvite(CommandSource source, PlayerRef actor, String[] args, Consumer<UUID> action) {
        if (args.length >= 2) {
            Optional<UUID> id = Ids.parseUuid(args[1]);
            if (id.isPresent()) {
                action.accept(id.get());
                return;
            }
            async(source, clanService.clanByName(args[1]), clan -> {
                if (clan.isEmpty()) {
                    send(source, "clan.not_found", Map.of("clan", args[1]));
                    return;
                }
                action.accept(clan.get().clanId());
            });
            return;
        }
        async(source, clanService.invitesOf(actor.uuid()), invites -> {
            if (invites.isEmpty()) {
                send(source, "clan.no_invites");
                return;
            }
            ClanInviteResponse newest = invites.getLast();
            action.accept(newest.clanId());
        });
    }

    private void outcome(CommandSource source, ClanActionResponse response, PlayerRef target) {
        Map<String, Object> ph = Map.of("player", target == null ? "" : target.name(),
                "clan", response.clan() == null ? "" : response.clan().name());
        switch (response.outcome()) {
            case OK, CREATED -> send(source, "clan.ok", ph);
            case NOT_IN_CLAN -> send(source, "clan.not_in_clan");
            case ALREADY_IN_CLAN -> send(source, "clan.already_in_clan");
            case TARGET_IN_CLAN -> send(source, "clan.target_in_clan", ph);
            case CLAN_NOT_FOUND -> send(source, "clan.not_found", ph);
            case NAME_TAKEN -> send(source, "clan.name_taken");
            case INVALID_NAME -> send(source, "clan.invalid_name");
            case NO_PERMISSION -> send(source, "clan.no_permission");
            case CLAN_FULL -> send(source, "clan.full");
            case ALREADY_INVITED -> send(source, "clan.already_invited", ph);
            case INVITE_NOT_FOUND -> send(source, "clan.invite_not_found");
            case INVITE_EXPIRED -> send(source, "clan.invite_expired");
            case ALREADY_REQUESTED -> send(source, "clan.already_requested");
            case REQUEST_NOT_FOUND -> send(source, "clan.request_not_found");
            case TARGET_NOT_MEMBER -> send(source, "clan.target_not_member", ph);
            case SELF_TARGET -> send(source, "clan.self");
            case OWNER_CANNOT_LEAVE -> send(source, "clan.owner_cannot_leave");
            case CANNOT_MODIFY_OWNER -> send(source, "clan.cannot_modify_owner");
            case ROLE_LIMIT -> send(source, "clan.role_limit");
            case TARGET_UNKNOWN -> send(source, "common.player_not_found", ph);
            case CONFLICT -> send(source, "social.retry");
        }
    }

    private void info(CommandSource source, PlayerRef actor, String clanName) {
        if (clanName != null) {
            async(source, clanService.clanByName(clanName), clan -> {
                if (clan.isEmpty()) {
                    send(source, "clan.not_found", Map.of("clan", clanName));
                    return;
                }
                printClan(source, clan.get(), null);
            });
            return;
        }
        async(source, clanService.overview(actor.uuid()), overview -> {
            if (overview.isEmpty()) {
                send(source, "clan.not_in_clan");
                async(source, clanService.invitesOf(actor.uuid()), invites -> {
                    for (ClanInviteResponse invite : invites) {
                        send(source, "clan.info.pending_invite", Map.of("clan", invite.clanName(), "player", invite.invitedByName()));
                    }
                });
                return;
            }
            printClan(source, overview.get().clan(), overview.get());
        });
    }

    private void printClan(CommandSource source, ClanResponse clan, ClanService.ClanOverview overview) {
        String owner = clan.members().stream().filter(m -> m.minecraftUuid().equals(clan.ownerUuid())).map(ClanMemberResponse::name).findFirst().orElse("?");
        send(source, "clan.info.header", Map.of("clan", clan.name(), "owner", owner, "members", clan.members().size(),
                "online", overview == null ? "?" : overview.onlineCount()));
        for (ClanMemberResponse member : clan.members()) {
            boolean online = overview != null && overview.online(member.minecraftUuid());
            send(source, "clan.info.member", Map.of("player", member.name(), "role", member.role(), "online", online));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(List.of("info", "create", "invite", "accept", "deny", "request", "requests", "acceptrequest", "denyrequest",
                    "kick", "promote", "demote", "leave", "disband"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            return filter(proxyServer.getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
        }
        return List.of();
    }
}
