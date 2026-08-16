package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import de.tasticgames.client.dto.network.AlphaAccessEntryResponse;
import de.tasticgames.proxy.access.AlphaAccessService;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.social.PlayerRef;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Ids;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /alphaaccess status|on|off|add <player|uuid> [note]|remove <player|uuid>|check <player|uuid>|list [page]|refresh
 */
public final class AlphaAccessCommand extends CommandSupport {

    private static final int PAGE_SIZE = 20;

    private final AlphaAccessService alphaAccessService;
    private final SocialPlayerLookup lookup;
    private final ProxyApiClient apiClient;

    public AlphaAccessCommand(AlphaAccessService alphaAccessService, SocialPlayerLookup lookup, ProxyApiClient apiClient,
                              ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.alphaAccessService = Objects.requireNonNull(alphaAccessService);
        this.lookup = Objects.requireNonNull(lookup);
        this.apiClient = Objects.requireNonNull(apiClient);
    }

    @Override
    protected String commandName() {
        return "alphaaccess";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyPermissions.ALPHA_ADMIN);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePermission(source, ProxyPermissions.ALPHA_ADMIN)) {
            return;
        }
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "status" -> send(source, "alpha.command.status", Map.of(
                    "state", alphaAccessService.enabled() ? "ENABLED" : "DISABLED",
                    "entries", alphaAccessService.allowedCount(),
                    "source", alphaAccessService.stateKnown() ? "central/cache" : "UNKNOWN (fail-safe deny)",
                    "refreshed", ago(alphaAccessService.lastRefreshAt())));
            case "on", "off" -> {
                boolean enable = sub.equals("on");
                adminTelemetry(source, sub, "");
                async(source, alphaAccessService.setEnabled(enable, actor(source)),
                        changed -> send(source, enable ? "alpha.command.enabled" : "alpha.command.disabled"));
            }
            case "add", "grant" -> {
                if (args.length < 2) {
                    send(source, "alpha.command.usage");
                    return;
                }
                String note = join(args, 2);
                resolve(source, args[1], ref -> {
                    adminTelemetry(source, "add", ref.uuid() + " " + note);
                    async(source, alphaAccessService.grant(ref.uuid(), actor(source), note.isBlank() ? null : note),
                            added -> send(source, added ? "alpha.command.added" : "alpha.command.already_added",
                                    Map.of("player", ref.name(), "uuid", ref.uuid())));
                });
            }
            case "remove", "revoke" -> {
                if (args.length < 2) {
                    send(source, "alpha.command.usage");
                    return;
                }
                resolve(source, args[1], ref -> {
                    adminTelemetry(source, "remove", ref.uuid().toString());
                    async(source, alphaAccessService.revoke(ref.uuid(), actor(source)),
                            removed -> send(source, removed ? "alpha.command.removed" : "alpha.command.not_listed",
                                    Map.of("player", ref.name(), "uuid", ref.uuid())));
                });
            }
            case "check" -> {
                if (args.length < 2) {
                    send(source, "alpha.command.usage");
                    return;
                }
                resolve(source, args[1], ref -> async(source, alphaAccessService.lookup(ref.uuid()), entry -> {
                    boolean cached = alphaAccessService.explicitlyAllowed(ref.uuid());
                    String detail = entry.map(e -> e.active() ? "active (granted by " + e.grantedBy() + ", " + ago(e.grantedAt()) + ")"
                            : "revoked by " + e.revokedBy()).orElse(cached ? "cached only" : "not listed");
                    send(source, "alpha.command.check", Map.of("player", ref.name(), "uuid", ref.uuid(),
                            "allowed", cached || entry.map(AlphaAccessEntryResponse::active).orElse(false), "detail", detail));
                }));
            }
            case "list" -> {
                int page = 0;
                if (args.length >= 2) {
                    try {
                        page = Math.max(0, Integer.parseInt(args[1]) - 1);
                    } catch (NumberFormatException ignored) {
                        // keep page 0
                    }
                }
                if (!apiClient.enabled()) {
                    List<UUID> local = alphaAccessService.allowedPlayers().stream().sorted().toList();
                    send(source, "alpha.command.list_header", Map.of("page", page + 1, "pages", Math.max(1, (local.size() + PAGE_SIZE - 1) / PAGE_SIZE), "total", local.size()));
                    local.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(uuid ->
                            send(source, "alpha.command.list_entry", Map.of("player", "?", "uuid", uuid, "grantedBy", "cache")));
                    return;
                }
                int finalPage = page;
                async(source, apiClient.call("alpha.list", client -> client.network().listAlphaAccessEntries(finalPage, PAGE_SIZE)), pageResponse -> {
                    send(source, "alpha.command.list_header", Map.of("page", pageResponse.page() + 1, "pages", Math.max(1, pageResponse.totalPages()), "total", pageResponse.totalItems()));
                    for (AlphaAccessEntryResponse entry : pageResponse.items()) {
                        send(source, "alpha.command.list_entry", Map.of("player", entry.knownName() == null ? "?" : entry.knownName(),
                                "uuid", entry.minecraftUuid(), "grantedBy", entry.grantedBy()));
                    }
                });
            }
            case "refresh" -> {
                alphaAccessService.refresh();
                send(source, "alpha.command.refreshed");
            }
            default -> send(source, "alpha.command.usage");
        }
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

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(List.of("status", "on", "off", "add", "remove", "check", "list", "refresh"), args.length == 0 ? "" : args[0]);
        }
        return List.of();
    }
}
