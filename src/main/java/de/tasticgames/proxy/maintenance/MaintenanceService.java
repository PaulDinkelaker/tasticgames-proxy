package de.tasticgames.proxy.maintenance;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.client.dto.network.MaintenanceStateResponse;
import de.tasticgames.client.dto.network.MaintenanceUpdateRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Maintenance mode 1.0: central state (API), local last-known-good cache file
 * ({@code maintenance-cache.properties}), periodic refresh, bus-triggered refresh,
 * bypass permission and localized denial. Never blocks the login thread.
 */
public final class MaintenanceService implements ProxyService {

    private static final String CACHE_FILE = "maintenance-cache.properties";

    private final ProxyServer proxyServer;
    private final ProxyConfigurationService configurationService;
    private final ProxyApiClient apiClient;
    private final ProxyScheduler scheduler;
    private final TelemetryService telemetry;
    private final ProxyMessages messages;
    private final Path dataDirectory;
    private final Logger logger;

    private volatile MaintenanceState state;
    private volatile Instant lastRefreshAt;
    private volatile boolean centralLoaded;
    private volatile Consumer<MaintenanceState> changeListener = ignored -> { };

    public MaintenanceService(ProxyServer proxyServer, ProxyConfigurationService configurationService,
                              ProxyApiClient apiClient, ProxyScheduler scheduler, TelemetryService telemetry,
                              ProxyMessages messages, Path dataDirectory, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
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
        return "maintenance-service";
    }

    @Override
    public void start() throws IOException {
        ProxyConfiguration.Maintenance config = configurationService.configuration().maintenance();
        state = readCache().orElse(MaintenanceState.local(config.fallbackEnabled(), config.kickOnlinePlayers()));
        logger.info("Maintenance mode initialized from {}: {}", centralLoaded ? "central" : "cache/config",
                state.enabled() ? "ENABLED" : "DISABLED");
        refresh();
        scheduler.repeat("maintenance-refresh", config.refreshInterval(), config.refreshInterval(), this::refresh);
    }

    @Override
    public void stop() {
    }

    public MaintenanceState state() {
        return state;
    }

    public boolean enabled() {
        return state.enabled();
    }

    public boolean centralLoaded() {
        return centralLoaded;
    }

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    public void onChange(Consumer<MaintenanceState> listener) {
        this.changeListener = Objects.requireNonNull(listener, "listener");
    }

    public boolean hasBypass(Player player) {
        return player.hasPermission(ProxyPermissions.MAINTENANCE_BYPASS);
    }

    public boolean mayJoin(Player player) {
        return !enabled() || hasBypass(player);
    }

    /** Central update (API). Falls back to a local change when the API is unavailable. */
    public CompletableFuture<MaintenanceState> update(boolean enabled, String reason, Instant expectedEndAt,
                                                      Boolean kickOnlinePlayers, String changedBy) {
        boolean kick = kickOnlinePlayers != null ? kickOnlinePlayers : state.kickOnlinePlayers();
        if (!apiClient.enabled()) {
            MaintenanceState local = new MaintenanceState(enabled, reason, expectedEndAt, kick, Instant.now(), changedBy, state.version());
            apply(local, false);
            return CompletableFuture.completedFuture(local);
        }
        return apiClient.call("maintenance.update", client -> client.network().updateMaintenance(
                        new MaintenanceUpdateRequest(enabled, reason, expectedEndAt, kick, changedBy)))
                .thenApply(response -> {
                    MaintenanceState fresh = fromResponse(response);
                    apply(fresh, true);
                    return fresh;
                });
    }

    /** Pulls the central state (async, best effort). */
    public void refresh() {
        if (!apiClient.enabled()) {
            return;
        }
        apiClient.call("maintenance.get", client -> client.network().getMaintenance()).whenComplete((response, throwable) -> {
            if (throwable != null) {
                logger.debug("Maintenance refresh failed (using last-known-good): {}", Throwables.rootMessage(throwable));
                return;
            }
            apply(fromResponse(response), true);
        });
    }

    private void apply(MaintenanceState fresh, boolean central) {
        MaintenanceState previous = state;
        state = fresh;
        if (central) {
            centralLoaded = true;
            lastRefreshAt = Instant.now();
        }
        writeCache(fresh);
        if (previous != null && previous.enabled() == fresh.enabled() && previous.sameAs(fresh)) {
            return;
        }
        if (previous == null || previous.enabled() != fresh.enabled()) {
            if (fresh.enabled()) {
                logger.warn("Maintenance mode ENABLED by {}{}.", fresh.changedBy(),
                        fresh.reason().isBlank() ? "" : " (" + fresh.reason() + ")");
                telemetry.publish(telemetry.event(TelemetryTypes.MAINTENANCE_ENABLED)
                        .attribute("changedBy", fresh.changedBy()).attribute("reason", fresh.reason()).build());
                if (fresh.kickOnlinePlayers()) {
                    kickNonBypassPlayers();
                }
            } else {
                logger.info("Maintenance mode DISABLED by {}.", fresh.changedBy());
                telemetry.publish(telemetry.event(TelemetryTypes.MAINTENANCE_DISABLED)
                        .attribute("changedBy", fresh.changedBy()).build());
            }
        }
        changeListener.accept(fresh);
    }

    public Component disconnectMessage(Player player) {
        return messages.get(player, "maintenance.kick", Map.of("reason", state.reason()));
    }

    public Component motd() {
        return messages.get(de.tasticgames.proxy.locale.ProxyLanguage.ENGLISH, "maintenance.motd",
                Map.of("reason", state.reason()));
    }

    public int kickNonBypassPlayers() {
        int disconnected = 0;
        for (Player player : proxyServer.getAllPlayers()) {
            if (hasBypass(player)) {
                continue;
            }
            player.disconnect(disconnectMessage(player));
            disconnected++;
        }
        logger.info("Disconnected {} player(s) because maintenance mode was enabled.", disconnected);
        return disconnected;
    }

    private static MaintenanceState fromResponse(MaintenanceStateResponse response) {
        return new MaintenanceState(response.enabled(), response.reason(), response.expectedEndAt(),
                response.kickOnlinePlayers(), response.changedAt(), response.changedBy(), response.version());
    }

    private Optional<MaintenanceState> readCache() {
        Path file = dataDirectory.resolve(CACHE_FILE);
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
            String end = p.getProperty("expectedEndAt", "");
            return Optional.of(new MaintenanceState(
                    Boolean.parseBoolean(p.getProperty("enabled", "false")),
                    p.getProperty("reason", ""),
                    end.isBlank() ? null : Instant.parse(end),
                    Boolean.parseBoolean(p.getProperty("kickOnlinePlayers", "false")),
                    Instant.parse(p.getProperty("changedAt", Instant.EPOCH.toString())),
                    p.getProperty("changedBy", "cache"),
                    Long.parseLong(p.getProperty("version", "-1"))));
        } catch (Exception e) {
            logger.warn("Could not read {} – ignoring cache: {}", CACHE_FILE, Throwables.rootMessage(e));
            return Optional.empty();
        }
    }

    private void writeCache(MaintenanceState s) {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(s.enabled()));
            p.setProperty("reason", s.reason());
            p.setProperty("expectedEndAt", s.expectedEndAt() == null ? "" : s.expectedEndAt().toString());
            p.setProperty("kickOnlinePlayers", Boolean.toString(s.kickOnlinePlayers()));
            p.setProperty("changedAt", s.changedAt().toString());
            p.setProperty("changedBy", s.changedBy());
            p.setProperty("version", Long.toString(s.version()));
            Files.createDirectories(dataDirectory);
            try (var writer = Files.newBufferedWriter(dataDirectory.resolve(CACHE_FILE), StandardCharsets.UTF_8)) {
                p.store(writer, "TasticProxy maintenance last-known-good cache (managed automatically)");
            }
        } catch (IOException e) {
            logger.debug("Could not write maintenance cache: {}", e.getMessage());
        }
    }

    Duration refreshInterval() {
        return configurationService.configuration().maintenance().refreshInterval();
    }
}
