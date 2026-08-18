package de.tasticgames.proxy.pass;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.TasticApiClient;
import de.tasticgames.client.dto.pass.PassAdminLevelRequest;
import de.tasticgames.client.dto.pass.PassAdminXpModeResponse;
import de.tasticgames.client.dto.pass.PassAdminXpRequest;
import de.tasticgames.client.dto.pass.PassPlayerResponse;
import de.tasticgames.client.dto.pass.PassPremiumGrantRequest;
import de.tasticgames.client.dto.pass.PassSeasonImportRequest;
import de.tasticgames.client.dto.pass.PassSeasonResponse;
import de.tasticgames.client.internal.HttpException;
import de.tasticgames.client.internal.JacksonSupport;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.util.Ids;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Season pass on the proxy. The player-facing {@code /pass} belongs to TasticLobby – Velocity
 * executes registered commands itself and never forwards them – so this service only owns the
 * administrative API access behind {@code /passadmin} and the network-wide level-up notifications.
 *
 * <p>Level-up commands arrive on {@link CommandTypes#PASS_LEVEL_UP} with a flat string payload:
 * {@code player} (UUID, optional when the command is addressed to a player), {@code fromLevel},
 * {@code toLevel}, {@code season} (display name or key) and {@code rewards} (already formatted).
 * Everything is parsed defensively – a malformed payload is ignored instead of failing the command.
 */
public final class PassService implements ProxyService {

    /** Result of a season import: the stored season, or the reason the API rejected the file. */
    public record ImportOutcome(PassSeasonResponse season, String rejection) {
        public boolean accepted() {
            return season != null;
        }
    }

    /** Same shape the pass API accepts for season and quest keys. */
    private static final Pattern SEASON_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String OUTSIDE_DATA_DIRECTORY = "Season files must stay inside the proxy data directory.";
    private static final int MAX_IMPORT_SUGGESTIONS = 50;
    private static final int MAX_REWARD_LENGTH = 200;
    private static final int MAX_SEASON_LENGTH = 64;

    private final ProxyServer proxyServer;
    private final ProxyApiClient apiClient;
    private final NetworkCommandBus bus;
    private final NetworkNotificationService notifications;
    private final Path dataDirectory;
    private final Logger logger;

    public PassService(ProxyServer proxyServer, ProxyApiClient apiClient, NetworkCommandBus bus,
                       NetworkNotificationService notifications, Path dataDirectory, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "pass-service";
    }

    @Override
    public void start() {
        bus.subscribe(CommandTypes.PASS_LEVEL_UP, this::handleLevelUp);
    }

    @Override
    public void stop() {
    }

    /** False while the API integration is disabled – the commands then answer "unavailable". */
    public boolean available() {
        return apiClient.enabled();
    }

    public static boolean validSeasonKey(String seasonKey) {
        return seasonKey != null && SEASON_KEY.matcher(seasonKey.trim()).matches();
    }

    // ------------------------------------------------------------------ administration

    /**
     * Grants the premium entitlement of {@code seasonKey} (the ACTIVE season when {@code null}).
     * There is no payment processing here: the generated order id is only the idempotency key.
     */
    public CompletableFuture<PassPlayerResponse> grantPremium(UUID player, String seasonKey, String actor) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(actor, "actor");
        PassPremiumGrantRequest request = new PassPremiumGrantRequest("admin-" + UUID.randomUUID(), "ADMIN", null, null,
                actor, seasonKey);
        return apiClient.call("pass.premium.grant", client -> client.pass().grantPremium(player, request));
    }

    public CompletableFuture<PassPlayerResponse> revokePremium(UUID player, String seasonKey) {
        Objects.requireNonNull(player, "player");
        return apiClient.call("pass.premium.revoke", client -> client.pass().revokePremium(player, seasonKey));
    }

    public CompletableFuture<PassPlayerResponse> addXp(UUID player, long amount, String actor) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(actor, "actor");
        PassAdminXpRequest request = new PassAdminXpRequest(UUID.randomUUID(), amount, PassAdminXpModeResponse.ADD, actor);
        return apiClient.call("pass.admin.xp", client -> client.pass().adminXp(player, request));
    }

    public CompletableFuture<PassPlayerResponse> setLevel(UUID player, int level, String actor) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(actor, "actor");
        PassAdminLevelRequest request = new PassAdminLevelRequest(UUID.randomUUID(), level, actor);
        return apiClient.call("pass.admin.level", client -> client.pass().adminLevel(player, request));
    }

    /** Empty when the API does not know that season key. */
    public CompletableFuture<Optional<PassSeasonResponse>> activateSeason(String seasonKey) {
        return season("pass.season.activate", client -> client.pass().activateSeason(seasonKey));
    }

    /** Empty when the API does not know that season key. */
    public CompletableFuture<Optional<PassSeasonResponse>> endSeason(String seasonKey) {
        return season("pass.season.end", client -> client.pass().endSeason(seasonKey));
    }

    /** A rejected import (HTTP 400) is an outcome, not a failure – the operator sees the reason. */
    public CompletableFuture<ImportOutcome> importSeason(PassSeasonImportRequest request) {
        Objects.requireNonNull(request, "request");
        return apiClient.call("pass.season.import", client -> client.pass().importSeason(request))
                .thenApply(season -> new ImportOutcome(season, null))
                .exceptionallyCompose(throwable -> {
                    Throwable cause = Throwables.unwrap(throwable);
                    if (cause instanceof HttpException http && http.statusCode() == 400) {
                        return CompletableFuture.completedFuture(new ImportOutcome(null, Throwables.rootMessage(cause)));
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    private CompletableFuture<Optional<PassSeasonResponse>> season(
            String operation, Function<TasticApiClient, CompletableFuture<PassSeasonResponse>> invocation) {
        return apiClient.call(operation, invocation)
                .thenApply(Optional::of)
                .exceptionallyCompose(throwable -> {
                    Throwable cause = Throwables.unwrap(throwable);
                    if (cause instanceof HttpException http && http.statusCode() == 404) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    // ------------------------------------------------------------------ season files

    /** Reads a season import file from the proxy data directory; paths escaping it are rejected. */
    public PassSeasonImportRequest readSeasonImport(String fileName) throws IOException {
        return readSeasonImport(dataDirectory, fileName);
    }

    /** JSON files directly inside the proxy data directory (tab completion of {@code season import}). */
    public List<String> importFiles() {
        Path base = dataDirectory.toAbsolutePath().normalize();
        try (Stream<Path> files = Files.list(base)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .limit(MAX_IMPORT_SUGGESTIONS)
                    .toList();
        } catch (IOException | RuntimeException e) {
            logger.debug("Cannot list season import files in {}: {}", base, Throwables.rootMessage(e));
            return List.of();
        }
    }

    static PassSeasonImportRequest readSeasonImport(Path dataDirectory, String fileName) throws IOException {
        Path file = resolveImportFile(dataDirectory, fileName);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        PassSeasonImportRequest request;
        try {
            request = JacksonSupport.objectMapper().readValue(json, PassSeasonImportRequest.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid season JSON: " + e.getOriginalMessage(), e);
        }
        if (request == null || !validSeasonKey(request.key())) {
            throw new IOException("The season file has no valid \"key\".");
        }
        return request;
    }

    /**
     * Resolves {@code fileName} inside the proxy data directory. Absolute paths, {@code ..}
     * segments and symlinks pointing out of the directory are refused.
     *
     * @throws NoSuchFileException when the file does not exist inside the data directory
     */
    static Path resolveImportFile(Path dataDirectory, String fileName) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("No season file given.");
        }
        Path base = dataDirectory.toAbsolutePath().normalize();
        Path file;
        try {
            file = base.resolve(fileName.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid season file name: " + fileName, e);
        }
        if (file.equals(base) || !file.startsWith(base)) {
            throw new IOException(OUTSIDE_DATA_DIRECTORY);
        }
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        if (!file.toRealPath().startsWith(base.toRealPath())) {
            throw new IOException(OUTSIDE_DATA_DIRECTORY);
        }
        return file;
    }

    // ------------------------------------------------------------------ level-up notifications

    private CompletableFuture<String> handleLevelUp(NetworkCommand command) {
        UUID player = levelUpPlayer(command);
        if (player == null) {
            return CompletableFuture.completedFuture("ignored: no player");
        }
        Map<String, String> placeholders = levelUpPlaceholders(command);
        if (placeholders == null) {
            return CompletableFuture.completedFuture("ignored: no level");
        }
        // a broadcast reaches every proxy: only the one the player is on delivers, otherwise the
        // notification service would send the same message back through the bus
        if (command.targetPlayerUuid() == null && proxyServer.getPlayer(player).isEmpty()) {
            return CompletableFuture.completedFuture("player not here");
        }
        notifications.notify(player, "pass.level_up", placeholders);
        String rewards = placeholders.get("rewards");
        if (!rewards.isBlank()) {
            notifications.notify(player, "pass.level_up.rewards", Map.of("rewards", rewards));
        }
        logger.debug("Pass level-up {} -> {} delivered to {}.", placeholders.get("from"), placeholders.get("to"), player);
        return CompletableFuture.completedFuture("notified");
    }

    static UUID levelUpPlayer(NetworkCommand command) {
        if (command.targetPlayerUuid() != null) {
            return command.targetPlayerUuid();
        }
        return Ids.parseUuid(command.get("player")).orElse(null);
    }

    /** @return the message placeholders, or {@code null} when the payload carries no usable level */
    static Map<String, String> levelUpPlaceholders(NetworkCommand command) {
        int toLevel = number(command.get("toLevel"));
        if (toLevel <= 0) {
            return null;
        }
        String season = shorten(command.get("season"), MAX_SEASON_LENGTH);
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("from", String.valueOf(Math.max(0, number(command.get("fromLevel")))));
        placeholders.put("to", String.valueOf(toLevel));
        placeholders.put("season", season.isBlank() ? "-" : season);
        placeholders.put("rewards", shorten(command.get("rewards"), MAX_REWARD_LENGTH));
        return placeholders;
    }

    private static int number(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
