package de.tasticgames.proxy.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, validated proxy configuration. Values marked reloadable are swapped by
 * {@link ProxyConfigurationService#reload()}; identity and API settings are fixed for the
 * lifetime of the process.
 */
public record ProxyConfiguration(
        Identity identity,
        Api api,
        Maintenance maintenance,
        AlphaAccess alphaAccess,
        Health health,
        Routing routing,
        Presence presence,
        Social social,
        Bus bus,
        Telemetry telemetry,
        Map<String, ServerDefinition> servers
) {

    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9._-]{1,64}$");
    public static final Pattern REGION_PATTERN = Pattern.compile("^[A-Z0-9_]{2,32}$");

    public ProxyConfiguration {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(maintenance, "maintenance");
        Objects.requireNonNull(alphaAccess, "alphaAccess");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(social, "social");
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(telemetry, "telemetry");
        servers = Map.copyOf(Objects.requireNonNull(servers, "servers"));
    }

    /** Fixed for the process lifetime. */
    public record Identity(
            String proxyId,
            String proxyName,
            String region,
            String environment,
            String host,
            Duration heartbeatInterval
    ) {
        public Identity {
            proxyId = requireId(proxyId, "proxy.instance-id");
            proxyName = Objects.requireNonNull(proxyName, "proxyName").trim();
            region = requireRegion(region, "proxy.region");
            environment = Objects.requireNonNull(environment, "environment").trim().toLowerCase(Locale.ROOT);
            if (!environment.matches("^[a-z0-9_-]{1,32}$")) {
                throw new IllegalArgumentException("proxy.environment must match [a-z0-9_-]{1,32}: " + environment);
            }
            host = host == null ? "" : host.trim();
            requirePositive(heartbeatInterval, "proxy.heartbeat-interval-seconds");
        }
    }

    public record Api(
            boolean enabled,
            String baseUrl,
            String serviceName,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean requireOnStartup
    ) {
        public Api {
            baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").trim();
            serviceName = Objects.requireNonNull(serviceName, "serviceName").trim();
            apiKey = Objects.requireNonNull(apiKey, "apiKey").trim();
            requirePositive(connectTimeout, "api.timeouts.connect-seconds");
            requirePositive(requestTimeout, "api.timeouts.request-seconds");
        }

        public boolean credentialsConfigured() {
            return !baseUrl.isBlank() && !serviceName.isBlank() && !apiKey.isBlank()
                    && !apiKey.equalsIgnoreCase("CHANGE_ME");
        }
    }

    /** Local defaults used only until the central maintenance state has been loaded. */
    public record Maintenance(
            boolean fallbackEnabled,
            boolean kickOnlinePlayers,
            Duration refreshInterval
    ) {
        public Maintenance {
            requirePositive(refreshInterval, "maintenance.refresh-interval-seconds");
        }
    }

    public record AlphaAccess(
            boolean fallbackEnabled,
            boolean denyWhenStateUnknown,
            Duration refreshInterval
    ) {
        public AlphaAccess {
            requirePositive(refreshInterval, "alpha-access.refresh-interval-seconds");
        }
    }

    /** Reloadable. */
    public record Health(
            Duration pingInterval,
            Duration pingTimeout,
            int failureThreshold,
            int recoveryThreshold,
            Duration staleThreshold,
            Duration degradedLatency
    ) {
        public Health {
            requirePositive(pingInterval, "health.ping-interval-seconds");
            requirePositive(pingTimeout, "health.ping-timeout-seconds");
            requireMin(failureThreshold, 1, "health.failure-threshold");
            requireMin(recoveryThreshold, 1, "health.recovery-threshold");
            requirePositive(staleThreshold, "health.stale-threshold-seconds");
            requirePositive(degradedLatency, "health.degraded-latency-millis");
        }
    }

    /** Reloadable. */
    public record Routing(
            String preferredRegion,
            boolean crossRegionFallback,
            int capacitySafetyMargin,
            String initialServerType,
            String fallbackServerType,
            Duration transferTimeout,
            Duration transferCooldown,
            Duration reservationTimeout,
            int loadWeight,
            int regionWeight,
            int serverWeightFactor,
            boolean reconnectToLastServer
    ) {
        public Routing {
            preferredRegion = requireRegion(preferredRegion, "routing.preferred-region");
            requireMin(capacitySafetyMargin, 0, "routing.capacity-safety-margin");
            initialServerType = Objects.requireNonNull(initialServerType, "initialServerType").trim().toUpperCase(Locale.ROOT);
            fallbackServerType = Objects.requireNonNull(fallbackServerType, "fallbackServerType").trim().toUpperCase(Locale.ROOT);
            requirePositive(transferTimeout, "routing.transfer-timeout-seconds");
            requireNonNegative(transferCooldown, "routing.transfer-cooldown-millis");
            requirePositive(reservationTimeout, "routing.reservation-timeout-seconds");
            requireMin(loadWeight, 0, "routing.scoring.load-weight");
            requireMin(regionWeight, 0, "routing.scoring.region-weight");
            requireMin(serverWeightFactor, 0, "routing.scoring.server-weight-factor");
        }
    }

    public record Presence(
            Duration heartbeatInterval,
            Duration staleAfter,
            Duration cleanupInterval
    ) {
        public Presence {
            requirePositive(heartbeatInterval, "presence.heartbeat-interval-seconds");
            requirePositive(staleAfter, "presence.stale-after-seconds");
            requirePositive(cleanupInterval, "presence.cleanup-interval-seconds");
        }
    }

    /** Reloadable. */
    public record Social(
            Duration friendRequestCooldown,
            int maxFriends,
            int partyMaxSize,
            Duration partyInviteTimeout,
            Duration partyIdleCleanup,
            int clanMemberLimit,
            Duration clanInviteTimeout,
            Duration inviteRateWindow,
            int inviteRateLimit,
            boolean friendJoinNotifications
    ) {
        public Social {
            requireNonNegative(friendRequestCooldown, "social.friend-request-cooldown-seconds");
            requireMin(maxFriends, 1, "social.max-friends");
            requireMin(partyMaxSize, 2, "social.party-max-size");
            requirePositive(partyInviteTimeout, "social.party-invite-timeout-seconds");
            requirePositive(partyIdleCleanup, "social.party-idle-cleanup-seconds");
            requireMin(clanMemberLimit, 2, "social.clan-member-limit");
            requirePositive(clanInviteTimeout, "social.clan-invite-timeout-seconds");
            requirePositive(inviteRateWindow, "social.invite-rate-window-seconds");
            requireMin(inviteRateLimit, 1, "social.invite-rate-limit");
        }
    }

    public record Bus(
            Duration pollInterval,
            int pollBatchSize,
            Duration commandTtl
    ) {
        public Bus {
            requirePositive(pollInterval, "bus.poll-interval-millis");
            requireMin(pollBatchSize, 1, "bus.poll-batch-size");
            requirePositive(commandTtl, "bus.command-ttl-seconds");
        }
    }

    public record Telemetry(
            boolean enabled,
            int queueCapacity,
            int batchSize,
            Duration flushInterval,
            Duration maxBackoff
    ) {
        public Telemetry {
            requireMin(queueCapacity, 100, "telemetry.queue-capacity");
            requireMin(batchSize, 1, "telemetry.batch-size");
            requirePositive(flushInterval, "telemetry.flush-interval-seconds");
            requirePositive(maxBackoff, "telemetry.max-backoff-seconds");
        }
    }

    /** Static per-server metadata (Velocity only knows name + address). Reloadable. */
    public record ServerDefinition(
            String serverId,
            String type,
            String region,
            int capacity,
            int weight,
            List<String> tags
    ) {
        public ServerDefinition {
            serverId = requireId(serverId, "servers.<id>");
            type = Objects.requireNonNull(type, "type").trim().toUpperCase(Locale.ROOT);
            region = requireRegion(region, "servers." + serverId + ".region");
            requireMin(capacity, 0, "servers." + serverId + ".capacity");
            requireMin(weight, 0, "servers." + serverId + ".weight");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String requireId(String value, String key) {
        Objects.requireNonNull(value, key);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(key + " must match [a-z0-9._-]{1,64}: " + value);
        }
        return normalized;
    }

    static String requireRegion(String value, String key) {
        Objects.requireNonNull(value, key);
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!REGION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(key + " must match [A-Z0-9_]{2,32}: " + value);
        }
        return normalized;
    }

    static void requirePositive(Duration duration, String key) {
        Objects.requireNonNull(duration, key);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(key + " must be positive.");
        }
    }

    static void requireNonNegative(Duration duration, String key) {
        Objects.requireNonNull(duration, key);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(key + " must not be negative.");
        }
    }

    static void requireMin(int value, int minimum, String key) {
        if (value < minimum) {
            throw new IllegalArgumentException(key + " must be >= " + minimum + " but was " + value + ".");
        }
    }
}
