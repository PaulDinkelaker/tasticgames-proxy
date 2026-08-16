package de.tasticgames.proxy.access;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.client.dto.network.AlphaAccessEntryResponse;
import de.tasticgames.client.dto.network.AlphaAccessGrantRequest;
import de.tasticgames.client.dto.network.AlphaAccessSnapshotResponse;
import de.tasticgames.client.dto.network.AlphaAccessStateUpdateRequest;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alpha access 1.0: central allow-list (API) with a local last-known-good snapshot cache
 * ({@code alpha-access-cache.txt}). Security-sensitive: when no central state was ever
 * loaded and no cache exists, joins are denied (configurable) instead of letting everyone in.
 */
public final class AlphaAccessService implements ProxyService {

    private static final String CACHE_FILE = "alpha-access-cache.txt";

    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final ProxyMessages messages;
    private final Path dataDirectory;
    private final Logger logger;

    private final Set<UUID> allowed = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;
    private volatile boolean stateKnown;
    private volatile long version = -1;
    private volatile Instant lastRefreshAt;

    public AlphaAccessService(ProxyConfigurationService configurationService, ProxyApiClient apiClient,
                              ProxyScheduler scheduler, TelemetryService telemetry, ProxyMessages messages,
                              Path dataDirectory, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "alpha-access-service";
    }

    @Override
    public void start() throws IOException {
        ProxyConfiguration.AlphaAccess config = configurationService.configuration().alphaAccess();
        enabled = config.fallbackEnabled();
        readCache();
        logger.info("Alpha access initialized: {} ({} cached entries, state {}).", enabled ? "ENABLED" : "DISABLED",
                allowed.size(), stateKnown ? "known" : "unknown");
        refresh();
        scheduler.repeat("alpha-access-refresh", config.refreshInterval(), config.refreshInterval(), this::refresh);
    }

    @Override
    public void stop() {
        allowed.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean stateKnown() {
        return stateKnown;
    }

    public long version() {
        return version;
    }

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    public int allowedCount() {
        return allowed.size();
    }

    public Set<UUID> allowedPlayers() {
        return Set.copyOf(allowed);
    }

    public boolean hasBypass(Player player) {
        return player.hasPermission(ProxyPermissions.ALPHA_BYPASS);
    }

    public boolean explicitlyAllowed(UUID uuid) {
        return allowed.contains(uuid);
    }

    /**
     * Join gate. Fail-safe: when alpha access is enabled but the state was never loaded
     * (no API, no cache) and {@code deny-when-state-unknown} is set, only bypass players may join.
     */
    public boolean mayJoin(Player player) {
        if (!enabled) {
            return true;
        }
        if (hasBypass(player)) {
            return true;
        }
        if (!stateKnown && configurationService.configuration().alphaAccess().denyWhenStateUnknown()) {
            return false;
        }
        return allowed.contains(player.getUniqueId());
    }

    public Component disconnectMessage(Player player) {
        return messages.get(player, "alpha.denied");
    }

    // ------------------------------------------------------------------ mutations (central)

    public CompletableFuture<Boolean> grant(UUID uuid, String grantedBy, String note) {
        if (!apiClient.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("API integration is disabled – alpha access cannot be changed."));
        }
        boolean wasAllowed = allowed.contains(uuid);
        return apiClient.call("alpha.grant", client -> client.network().grantAlphaAccess(uuid, new AlphaAccessGrantRequest(grantedBy, note)))
                .thenApply(entry -> {
                    allowed.add(uuid);
                    writeCache();
                    telemetry.publish(telemetry.event(TelemetryTypes.ALPHA_ACCESS_GRANTED).player(uuid)
                            .attribute("grantedBy", grantedBy).build());
                    logger.info("Alpha access granted to {} by {}.", uuid, grantedBy);
                    return !wasAllowed;
                });
    }

    public CompletableFuture<Boolean> revoke(UUID uuid, String revokedBy) {
        if (!apiClient.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("API integration is disabled – alpha access cannot be changed."));
        }
        return apiClient.call("alpha.revoke", client -> client.network().revokeAlphaAccess(uuid, revokedBy))
                .thenApply(entry -> {
                    boolean removed = allowed.remove(uuid);
                    writeCache();
                    if (entry.isPresent()) {
                        telemetry.publish(telemetry.event(TelemetryTypes.ALPHA_ACCESS_REVOKED).player(uuid)
                                .attribute("revokedBy", revokedBy).build());
                        logger.info("Alpha access revoked for {} by {}.", uuid, revokedBy);
                    }
                    return removed || entry.map(AlphaAccessEntryResponse::active).map(a -> !a).orElse(false);
                });
    }

    public CompletableFuture<Boolean> setEnabled(boolean value, String changedBy) {
        if (!apiClient.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("API integration is disabled – alpha access cannot be changed."));
        }
        return apiClient.call("alpha.state", client -> client.network().updateAlphaAccessState(new AlphaAccessStateUpdateRequest(value, changedBy)))
                .thenApply(state -> {
                    boolean changed = enabled != state.enabled();
                    enabled = state.enabled();
                    stateKnown = true;
                    version = state.version();
                    writeCache();
                    if (changed) {
                        logger.info("Alpha access {} by {}.", enabled ? "ENABLED" : "DISABLED", changedBy);
                    }
                    return changed;
                });
    }

    public CompletableFuture<Optional<AlphaAccessEntryResponse>> lookup(UUID uuid) {
        if (!apiClient.enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return apiClient.call("alpha.lookup", client -> client.network().findAlphaAccessEntry(uuid));
    }

    /** Pulls the central snapshot (async). */
    public void refresh() {
        if (!apiClient.enabled()) {
            return;
        }
        apiClient.call("alpha.snapshot", client -> client.network().getAlphaAccessSnapshot()).whenComplete((snapshot, throwable) -> {
            if (throwable != null) {
                logger.debug("Alpha access refresh failed (using last-known-good): {}", Throwables.rootMessage(throwable));
                return;
            }
            apply(snapshot);
        });
    }

    private synchronized void apply(AlphaAccessSnapshotResponse snapshot) {
        boolean previouslyEnabled = enabled;
        allowed.retainAll(snapshot.activeUuids());
        allowed.addAll(snapshot.activeUuids());
        enabled = snapshot.enabled();
        stateKnown = true;
        version = snapshot.version();
        lastRefreshAt = Instant.now();
        writeCache();
        if (previouslyEnabled != enabled) {
            logger.info("Alpha access is now {} (central).", enabled ? "ENABLED" : "DISABLED");
        }
    }

    // ------------------------------------------------------------------ cache

    private void readCache() {
        Path file = dataDirectory.resolve(CACHE_FILE);
        if (Files.notExists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("enabled=")) {
                    enabled = Boolean.parseBoolean(line.substring("enabled=".length()));
                    stateKnown = true;
                    continue;
                }
                if (line.startsWith("version=")) {
                    version = Long.parseLong(line.substring("version=".length()));
                    continue;
                }
                try {
                    allowed.add(UUID.fromString(line));
                } catch (IllegalArgumentException ignored) {
                    logger.warn("Ignoring invalid UUID in {}: {}", CACHE_FILE, line);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", CACHE_FILE, e.getMessage());
        }
    }

    private synchronized void writeCache() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("# TasticProxy alpha access last-known-good cache (managed automatically)");
            lines.add("enabled=" + enabled);
            lines.add("version=" + version);
            allowed.stream().map(UUID::toString).sorted().forEach(lines::add);
            Files.createDirectories(dataDirectory);
            Files.write(dataDirectory.resolve(CACHE_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Could not write alpha access cache: {}", e.getMessage());
        }
    }

    Map<String, Object> diagnostics() {
        return Map.of("enabled", enabled, "stateKnown", stateKnown, "entries", allowed.size(), "version", version);
    }
}
