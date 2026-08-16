package de.tasticgames.proxy.config;

import de.tasticgames.proxy.service.ProxyService;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Loads {@code config.properties}, applies environment overrides, validates fail-fast and
 * exposes an immutable {@link ProxyConfiguration}. Secrets are never logged.
 *
 * <p>Environment overrides (take precedence over the file):
 * {@code TASTIC_API_BASE_URL}, {@code TASTIC_API_SERVICE}, {@code TASTIC_API_KEY},
 * {@code TASTIC_PROXY_ID}, {@code TASTIC_PROXY_REGION}, {@code TASTIC_PROXY_ENVIRONMENT},
 * {@code TASTIC_PROXY_HOST}.</p>
 */
public class ProxyConfigurationService implements ProxyService {

    private static final String FILE_NAME = "config.properties";
    private static final String INSTANCE_FILE = "instance-id";

    private final Path dataDirectory;
    private final Logger logger;
    private final Function<String, String> environment;

    private volatile ProxyConfiguration configuration;

    public ProxyConfigurationService(Path dataDirectory, Logger logger) {
        this(dataDirectory, logger, System::getenv);
    }

    public ProxyConfigurationService(Path dataDirectory, Logger logger, Function<String, String> environment) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public String id() {
        return "configuration-service";
    }

    @Override
    public synchronized void start() throws IOException {
        Files.createDirectories(dataDirectory);
        Properties properties = loadOrCreate();
        configuration = parse(properties, null);
        logger.info("Configuration loaded: proxy={} region={} environment={} servers={}",
                configuration.identity().proxyId(), configuration.identity().region(),
                configuration.identity().environment(), configuration.servers().size());
    }

    @Override
    public synchronized void stop() {
        configuration = null;
    }

    public ProxyConfiguration configuration() {
        ProxyConfiguration current = configuration;
        if (current == null) {
            throw new IllegalStateException("Configuration service is not started.");
        }
        return current;
    }

    /**
     * Re-reads the file and swaps only reloadable sections (health, routing, social, servers,
     * telemetry limits). Identity and API settings are kept from the running configuration.
     *
     * @return list of section names that changed
     */
    public synchronized List<String> reload() throws IOException {
        ProxyConfiguration current = configuration();
        Properties properties = loadOrCreate();
        ProxyConfiguration fresh = parse(properties, current);
        List<String> changed = new ArrayList<>();
        if (!fresh.health().equals(current.health())) changed.add("health");
        if (!fresh.routing().equals(current.routing())) changed.add("routing");
        if (!fresh.social().equals(current.social())) changed.add("social");
        if (!fresh.servers().equals(current.servers())) changed.add("servers");
        if (!fresh.telemetry().equals(current.telemetry())) changed.add("telemetry");
        if (!fresh.maintenance().equals(current.maintenance())) changed.add("maintenance");
        if (!fresh.alphaAccess().equals(current.alphaAccess())) changed.add("alpha-access");
        configuration = new ProxyConfiguration(
                current.identity(), current.api(), fresh.maintenance(), fresh.alphaAccess(), fresh.health(),
                fresh.routing(), current.presence(), fresh.social(), current.bus(), fresh.telemetry(), fresh.servers()
        );
        return changed;
    }

    // ------------------------------------------------------------------ parsing

    ProxyConfiguration parse(Properties p, ProxyConfiguration keepFixed) {
        String proxyId = firstNonBlank(env("TASTIC_PROXY_ID"), value(p, "proxy.instance-id"));
        String region = firstNonBlank(env("TASTIC_PROXY_REGION"), value(p, "proxy.region"));
        String environmentName = firstNonBlank(env("TASTIC_PROXY_ENVIRONMENT"), value(p, "proxy.environment"));
        String host = firstNonBlank(env("TASTIC_PROXY_HOST"), value(p, "proxy.host"));

        ProxyConfiguration.Identity identity = keepFixed != null ? keepFixed.identity() : new ProxyConfiguration.Identity(
                proxyId,
                firstNonBlank(value(p, "proxy.name"), proxyId),
                region,
                environmentName,
                host,
                seconds(p, "proxy.heartbeat-interval-seconds")
        );

        ProxyConfiguration.Api api = keepFixed != null ? keepFixed.api() : new ProxyConfiguration.Api(
                bool(p, "api.enabled"),
                firstNonBlank(env("TASTIC_API_BASE_URL"), value(p, "api.base-url")),
                firstNonBlank(env("TASTIC_API_SERVICE"), value(p, "api.authentication.service-name")),
                firstNonBlank(env("TASTIC_API_KEY"), value(p, "api.authentication.api-key")),
                seconds(p, "api.timeouts.connect-seconds"),
                seconds(p, "api.timeouts.request-seconds"),
                bool(p, "api.require-on-startup")
        );

        ProxyConfiguration.Maintenance maintenance = new ProxyConfiguration.Maintenance(
                bool(p, "maintenance.fallback-enabled"),
                bool(p, "maintenance.kick-online-players"),
                seconds(p, "maintenance.refresh-interval-seconds")
        );

        ProxyConfiguration.AlphaAccess alpha = new ProxyConfiguration.AlphaAccess(
                bool(p, "alpha-access.fallback-enabled"),
                bool(p, "alpha-access.deny-when-state-unknown"),
                seconds(p, "alpha-access.refresh-interval-seconds")
        );

        ProxyConfiguration.Health health = new ProxyConfiguration.Health(
                seconds(p, "health.ping-interval-seconds"),
                seconds(p, "health.ping-timeout-seconds"),
                integer(p, "health.failure-threshold"),
                integer(p, "health.recovery-threshold"),
                seconds(p, "health.stale-threshold-seconds"),
                millis(p, "health.degraded-latency-millis")
        );

        ProxyConfiguration.Routing routing = new ProxyConfiguration.Routing(
                firstNonBlank(value(p, "routing.preferred-region"), identity.region()),
                bool(p, "routing.cross-region-fallback"),
                integer(p, "routing.capacity-safety-margin"),
                value(p, "routing.initial-server-type"),
                value(p, "routing.fallback-server-type"),
                seconds(p, "routing.transfer-timeout-seconds"),
                millis(p, "routing.transfer-cooldown-millis"),
                seconds(p, "routing.reservation-timeout-seconds"),
                integer(p, "routing.scoring.load-weight"),
                integer(p, "routing.scoring.region-weight"),
                integer(p, "routing.scoring.server-weight-factor"),
                bool(p, "routing.reconnect-to-last-server")
        );

        ProxyConfiguration.Presence presence = keepFixed != null ? keepFixed.presence() : new ProxyConfiguration.Presence(
                seconds(p, "presence.heartbeat-interval-seconds"),
                seconds(p, "presence.stale-after-seconds"),
                seconds(p, "presence.cleanup-interval-seconds")
        );

        ProxyConfiguration.Social social = new ProxyConfiguration.Social(
                seconds(p, "social.friend-request-cooldown-seconds"),
                integer(p, "social.max-friends"),
                integer(p, "social.party-max-size"),
                seconds(p, "social.party-invite-timeout-seconds"),
                seconds(p, "social.party-idle-cleanup-seconds"),
                integer(p, "social.clan-member-limit"),
                seconds(p, "social.clan-invite-timeout-seconds"),
                seconds(p, "social.invite-rate-window-seconds"),
                integer(p, "social.invite-rate-limit"),
                bool(p, "social.friend-join-notifications")
        );

        ProxyConfiguration.Bus bus = keepFixed != null ? keepFixed.bus() : new ProxyConfiguration.Bus(
                millis(p, "bus.poll-interval-millis"),
                integer(p, "bus.poll-batch-size"),
                seconds(p, "bus.command-ttl-seconds")
        );

        ProxyConfiguration.Telemetry telemetry = new ProxyConfiguration.Telemetry(
                bool(p, "telemetry.enabled"),
                integer(p, "telemetry.queue-capacity"),
                integer(p, "telemetry.batch-size"),
                seconds(p, "telemetry.flush-interval-seconds"),
                seconds(p, "telemetry.max-backoff-seconds")
        );

        return new ProxyConfiguration(identity, api, maintenance, alpha, health, routing, presence, social, bus,
                telemetry, parseServers(p));
    }

    private Map<String, ProxyConfiguration.ServerDefinition> parseServers(Properties p) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("servers.")) {
                String rest = key.substring("servers.".length());
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    ids.add(rest.substring(0, dot));
                }
            }
        }
        Map<String, ProxyConfiguration.ServerDefinition> servers = new LinkedHashMap<>();
        for (String id : ids) {
            String prefix = "servers." + id + ".";
            String tagValue = value(p, prefix + "tags");
            List<String> tags = tagValue.isBlank() ? List.of()
                    : Arrays.stream(tagValue.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            ProxyConfiguration.ServerDefinition definition = new ProxyConfiguration.ServerDefinition(
                    id,
                    firstNonBlank(value(p, prefix + "type"), "OTHER"),
                    firstNonBlank(value(p, prefix + "region"), value(p, "proxy.region")),
                    integerOr(p, prefix + "capacity", 100),
                    integerOr(p, prefix + "weight", 100),
                    tags
            );
            servers.put(definition.serverId(), definition);
        }
        return servers;
    }

    // ------------------------------------------------------------------ file handling

    private Properties loadOrCreate() throws IOException {
        Path file = dataDirectory.resolve(FILE_NAME);
        Properties properties = new Properties();
        boolean created = Files.notExists(file);
        if (!created) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        boolean changed = addMissingDefaults(properties);
        if (created || changed) {
            store(file, properties);
            if (created) {
                logger.warn("Created default {} – please review proxy.instance-id, proxy.region and API credentials.", FILE_NAME);
            }
        }
        return properties;
    }

    private boolean addMissingDefaults(Properties p) throws IOException {
        boolean changed = false;
        changed |= putDefault(p, "proxy.instance-id", generatedInstanceId());
        changed |= putDefault(p, "proxy.name", "TasticProxy");
        changed |= putDefault(p, "proxy.region", "EU_FRANKFURT");
        changed |= putDefault(p, "proxy.environment", "alpha");
        changed |= putDefault(p, "proxy.host", "");
        changed |= putDefault(p, "proxy.heartbeat-interval-seconds", "15");

        changed |= putDefault(p, "api.enabled", "true");
        changed |= putDefault(p, "api.base-url", "https://api.tasticgames.de/");
        changed |= putDefault(p, "api.authentication.service-name", "tastic-proxy");
        changed |= putDefault(p, "api.authentication.api-key", "CHANGE_ME");
        changed |= putDefault(p, "api.timeouts.connect-seconds", "5");
        changed |= putDefault(p, "api.timeouts.request-seconds", "10");
        changed |= putDefault(p, "api.require-on-startup", "false");

        changed |= putDefault(p, "maintenance.fallback-enabled", "false");
        changed |= putDefault(p, "maintenance.kick-online-players", "false");
        changed |= putDefault(p, "maintenance.refresh-interval-seconds", "30");

        changed |= putDefault(p, "alpha-access.fallback-enabled", "true");
        changed |= putDefault(p, "alpha-access.deny-when-state-unknown", "true");
        changed |= putDefault(p, "alpha-access.refresh-interval-seconds", "60");

        changed |= putDefault(p, "health.ping-interval-seconds", "10");
        changed |= putDefault(p, "health.ping-timeout-seconds", "3");
        changed |= putDefault(p, "health.failure-threshold", "3");
        changed |= putDefault(p, "health.recovery-threshold", "2");
        changed |= putDefault(p, "health.stale-threshold-seconds", "60");
        changed |= putDefault(p, "health.degraded-latency-millis", "750");

        changed |= putDefault(p, "routing.preferred-region", "");
        changed |= putDefault(p, "routing.cross-region-fallback", "true");
        changed |= putDefault(p, "routing.capacity-safety-margin", "2");
        changed |= putDefault(p, "routing.initial-server-type", "LOBBY");
        changed |= putDefault(p, "routing.fallback-server-type", "LOBBY");
        changed |= putDefault(p, "routing.transfer-timeout-seconds", "15");
        changed |= putDefault(p, "routing.transfer-cooldown-millis", "1500");
        changed |= putDefault(p, "routing.reservation-timeout-seconds", "20");
        changed |= putDefault(p, "routing.scoring.load-weight", "100");
        changed |= putDefault(p, "routing.scoring.region-weight", "200");
        changed |= putDefault(p, "routing.scoring.server-weight-factor", "1");
        changed |= putDefault(p, "routing.reconnect-to-last-server", "false");

        changed |= putDefault(p, "presence.heartbeat-interval-seconds", "60");
        changed |= putDefault(p, "presence.stale-after-seconds", "180");
        changed |= putDefault(p, "presence.cleanup-interval-seconds", "120");

        changed |= putDefault(p, "social.friend-request-cooldown-seconds", "10");
        changed |= putDefault(p, "social.max-friends", "200");
        changed |= putDefault(p, "social.party-max-size", "8");
        changed |= putDefault(p, "social.party-invite-timeout-seconds", "60");
        changed |= putDefault(p, "social.party-idle-cleanup-seconds", "300");
        changed |= putDefault(p, "social.clan-member-limit", "50");
        changed |= putDefault(p, "social.clan-invite-timeout-seconds", "300");
        changed |= putDefault(p, "social.invite-rate-window-seconds", "60");
        changed |= putDefault(p, "social.invite-rate-limit", "10");
        changed |= putDefault(p, "social.friend-join-notifications", "true");

        changed |= putDefault(p, "bus.poll-interval-millis", "1000");
        changed |= putDefault(p, "bus.poll-batch-size", "50");
        changed |= putDefault(p, "bus.command-ttl-seconds", "60");

        changed |= putDefault(p, "telemetry.enabled", "true");
        changed |= putDefault(p, "telemetry.queue-capacity", "5000");
        changed |= putDefault(p, "telemetry.batch-size", "200");
        changed |= putDefault(p, "telemetry.flush-interval-seconds", "5");
        changed |= putDefault(p, "telemetry.max-backoff-seconds", "60");

        changed |= putDefault(p, "servers.lobby.type", "LOBBY");
        changed |= putDefault(p, "servers.lobby.region", value(p, "proxy.region"));
        changed |= putDefault(p, "servers.lobby.capacity", "200");
        changed |= putDefault(p, "servers.lobby.weight", "100");
        return changed;
    }

    /** Stable per-installation instance id, generated once and kept in the data directory. */
    private String generatedInstanceId() throws IOException {
        Path file = dataDirectory.resolve(INSTANCE_FILE);
        if (Files.exists(file)) {
            String existing = Files.readString(file).trim().toLowerCase(Locale.ROOT);
            if (ProxyConfiguration.ID_PATTERN.matcher(existing).matches()) {
                return existing;
            }
        }
        String generated = "proxy-" + UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(file, generated);
        return generated;
    }

    private void store(Path file, Properties properties) throws IOException {
        Path temporary = dataDirectory.resolve(FILE_NAME + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "TasticProxy configuration – secrets may be supplied via TASTIC_API_KEY env var");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // ------------------------------------------------------------------ value helpers

    private String env(String name) {
        String value = environment.apply(name);
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean putDefault(Properties p, String key, String value) {
        if (p.containsKey(key)) {
            return false;
        }
        p.setProperty(key, value);
        return true;
    }

    private static String value(Properties p, String key) {
        String value = p.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private static boolean bool(Properties p, String key) {
        String value = value(p, key);
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Configuration value " + key + " must be true or false, but was: " + value);
    }

    private static int integer(Properties p, String key) {
        String value = value(p, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration value " + key + " must be an integer, but was: " + value, e);
        }
    }

    private static int integerOr(Properties p, String key, int fallback) {
        String value = value(p, key);
        return value.isBlank() ? fallback : integer(p, key);
    }

    private static Duration seconds(Properties p, String key) {
        return Duration.ofSeconds(integer(p, key));
    }

    private static Duration millis(Properties p, String key) {
        return Duration.ofMillis(integer(p, key));
    }
}
