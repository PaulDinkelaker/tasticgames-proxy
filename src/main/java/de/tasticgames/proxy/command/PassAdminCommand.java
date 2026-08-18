package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.pass.PassPlayerResponse;
import de.tasticgames.client.dto.pass.PassSeasonImportRequest;
import de.tasticgames.client.dto.pass.PassSeasonResponse;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.pass.PassService;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Ids;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /passadmin grant|revoke <player> [season] | addxp <player> <amount> | setlevel <player> <level>
 * | season <activate|end> <season> | season import <file>
 *
 * <p>The player-facing {@code /pass} stays with TasticLobby: Velocity executes registered commands
 * itself and never forwards them, so a proxy-side {@code /pass} would permanently shadow it.
 */
public final class PassAdminCommand extends CommandSupport {

    private static final List<String> SUBCOMMANDS = List.of("grant", "revoke", "addxp", "setlevel", "season");
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("grant", "revoke", "addxp", "setlevel");
    private static final List<String> SEASON_ACTIONS = List.of("activate", "end", "import");

    private final PassService passService;
    private final SocialPlayerLookup lookup;
    private final ProxyServer proxyServer;

    public PassAdminCommand(PassService passService, SocialPlayerLookup lookup, ProxyServer proxyServer,
                            ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.passService = Objects.requireNonNull(passService);
        this.lookup = Objects.requireNonNull(lookup);
        this.proxyServer = Objects.requireNonNull(proxyServer);
    }

    @Override
    protected String commandName() {
        return "passadmin";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyPermissions.PASS_ADMIN);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePermission(source, ProxyPermissions.PASS_ADMIN)) {
            return;
        }
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "grant" -> premium(source, args, true);
            case "revoke" -> premium(source, args, false);
            case "addxp" -> addXp(source, args);
            case "setlevel" -> setLevel(source, args);
            case "season" -> season(source, args);
            default -> send(source, "pass.admin.usage");
        }
    }

    // ------------------------------------------------------------------ players

    private void premium(CommandSource source, String[] args, boolean grant) {
        if (!ready(source) || !requireArgs(source, args, 2)) {
            return;
        }
        String seasonKey = args.length >= 3 ? args[2] : null;
        if (seasonKey != null && !PassService.validSeasonKey(seasonKey)) {
            send(source, "pass.admin.invalid_season", Map.of("season", seasonKey));
            return;
        }
        resolve(source, args[1], ref -> {
            adminTelemetry(source, grant ? "grant" : "revoke", ref.uuid() + (seasonKey == null ? "" : " " + seasonKey));
            CompletableFuture<PassPlayerResponse> future = grant
                    ? passService.grantPremium(ref.uuid(), seasonKey, actor(source))
                    : passService.revokePremium(ref.uuid(), seasonKey);
            async(source, future, state -> state(source, state, grant ? "pass.admin.granted" : "pass.admin.revoked", ref));
        });
    }

    private void addXp(CommandSource source, String[] args) {
        if (!ready(source) || !requireArgs(source, args, 3)) {
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            send(source, "pass.admin.invalid_number", Map.of("value", args[2]));
            return;
        }
        resolve(source, args[1], ref -> {
            adminTelemetry(source, "addxp", ref.uuid() + " " + amount);
            async(source, passService.addXp(ref.uuid(), amount, actor(source)),
                    state -> state(source, state, "pass.admin.xp_added", ref));
        });
    }

    private void setLevel(CommandSource source, String[] args) {
        if (!ready(source) || !requireArgs(source, args, 3)) {
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            send(source, "pass.admin.invalid_number", Map.of("value", args[2]));
            return;
        }
        if (level < 1) {
            send(source, "pass.admin.invalid_number", Map.of("value", args[2]));
            return;
        }
        resolve(source, args[1], ref -> {
            adminTelemetry(source, "setlevel", ref.uuid() + " " + level);
            async(source, passService.setLevel(ref.uuid(), level, actor(source)),
                    state -> state(source, state, "pass.admin.level_set", ref));
        });
    }

    /** Reports the resulting pass state; without an ACTIVE season the API answers with an empty one. */
    private void state(CommandSource source, PassPlayerResponse state, String key, PlayerRef player) {
        if (!state.seasonActive()) {
            send(source, "pass.admin.no_season");
            return;
        }
        send(source, key, Map.of("player", player.name(), "season", seasonName(state), "level", state.level(),
                "maxLevel", state.maxLevel(), "totalXp", state.totalXp(), "premium", state.premium()));
    }

    // ------------------------------------------------------------------ seasons

    private void season(CommandSource source, String[] args) {
        if (!ready(source)) {
            return;
        }
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "activate", "end" -> {
                if (!requireArgs(source, args, 3)) {
                    return;
                }
                String seasonKey = args[2];
                if (!PassService.validSeasonKey(seasonKey)) {
                    send(source, "pass.admin.invalid_season", Map.of("season", seasonKey));
                    return;
                }
                boolean activate = action.equals("activate");
                adminTelemetry(source, "season " + action, seasonKey);
                CompletableFuture<Optional<PassSeasonResponse>> future = activate
                        ? passService.activateSeason(seasonKey)
                        : passService.endSeason(seasonKey);
                async(source, future, season -> {
                    if (season.isEmpty()) {
                        send(source, "pass.admin.season_unknown", Map.of("season", seasonKey));
                        return;
                    }
                    send(source, activate ? "pass.admin.season_activated" : "pass.admin.season_ended",
                            Map.of("season", season.get().displayName(), "key", season.get().key(),
                                    "state", season.get().state()));
                });
            }
            case "import" -> importSeason(source, join(args, 2));
            default -> send(source, "pass.admin.usage");
        }
    }

    private void importSeason(CommandSource source, String fileName) {
        if (fileName.isBlank()) {
            send(source, "pass.admin.usage");
            return;
        }
        PassSeasonImportRequest request;
        try {
            request = passService.readSeasonImport(fileName);
        } catch (NoSuchFileException e) {
            send(source, "pass.admin.import_not_found", Map.of("file", fileName));
            return;
        } catch (IOException e) {
            send(source, "pass.admin.import_invalid", Map.of("file", fileName, "error", Throwables.rootMessage(e)));
            return;
        }
        adminTelemetry(source, "season import", fileName + " -> " + request.key());
        async(source, passService.importSeason(request), outcome -> {
            if (!outcome.accepted()) {
                send(source, "pass.admin.import_rejected", Map.of("file", fileName, "error", outcome.rejection()));
                return;
            }
            PassSeasonResponse season = outcome.season();
            send(source, "pass.admin.season_imported", Map.of("season", season.displayName(), "key", season.key(),
                    "state", season.state(), "tiers", season.tiers().size(), "quests", season.quests().size()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private boolean ready(CommandSource source) {
        if (passService.available()) {
            return true;
        }
        unavailable(source, "API integration disabled - passService offline");
        return false;
    }

    private boolean requireArgs(CommandSource source, String[] args, int required) {
        if (args.length >= required) {
            return true;
        }
        send(source, "pass.admin.usage");
        return false;
    }

    private void resolve(CommandSource source, String input, java.util.function.Consumer<PlayerRef> then) {
        Optional<UUID> uuid = Ids.parseUuid(input);
        CompletableFuture<Optional<PlayerRef>> future = uuid.isPresent()
                ? lookup.byUuid(uuid.get()).thenApply(ref -> Optional.of(ref.orElse(new PlayerRef(uuid.get(), "?"))))
                : lookup.byNameOrUuid(input);
        async(source, future, ref -> {
            if (ref.isEmpty()) {
                send(source, "common.player_not_found", Map.of("player", input));
                return;
            }
            then.accept(ref.get());
        });
    }

    private static String seasonName(PassPlayerResponse state) {
        if (state.seasonDisplayName() != null && !state.seasonDisplayName().isBlank()) {
            return state.seasonDisplayName();
        }
        return state.seasonKey() == null ? "-" : state.seasonKey();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("season")) {
                return filter(SEASON_ACTIONS, args[1]);
            }
            if (PLAYER_SUBCOMMANDS.contains(sub)) {
                return filter(proxyServer.getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
            }
            return List.of();
        }
        if (args.length == 3 && sub.equals("season") && args[1].equalsIgnoreCase("import")) {
            return filter(passService.importFiles(), args[2]);
        }
        return List.of();
    }
}
