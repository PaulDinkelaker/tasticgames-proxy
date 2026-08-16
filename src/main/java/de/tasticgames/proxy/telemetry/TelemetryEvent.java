package de.tasticgames.proxy.telemetry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable telemetry event (proxy id/region/environment are added by the service).
 */
public record TelemetryEvent(
        String type,
        Instant timestamp,
        UUID playerUuid,
        UUID sessionId,
        String serverId,
        String correlationId,
        String outcome,
        Long durationMillis,
        Map<String, String> attributes
) {
    public TelemetryEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final String type;
        private UUID playerUuid;
        private UUID sessionId;
        private String serverId;
        private String correlationId;
        private String outcome;
        private Long durationMillis;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder player(UUID playerUuid) { this.playerUuid = playerUuid; return this; }
        public Builder session(UUID sessionId) { this.sessionId = sessionId; return this; }
        public Builder server(String serverId) { this.serverId = serverId; return this; }
        public Builder correlation(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder outcome(String outcome) { this.outcome = outcome; return this; }
        public Builder outcome(Enum<?> outcome) { this.outcome = outcome == null ? null : outcome.name(); return this; }
        public Builder duration(long millis) { this.durationMillis = millis; return this; }
        public Builder attribute(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, String.valueOf(value));
            }
            return this;
        }

        public TelemetryEvent build() {
            return new TelemetryEvent(type, Instant.now(), playerUuid, sessionId, serverId, correlationId, outcome,
                    durationMillis, attributes);
        }
    }
}
