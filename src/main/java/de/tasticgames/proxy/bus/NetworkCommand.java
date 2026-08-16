package de.tasticgames.proxy.bus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A command received from the network command bus.
 */
public record NetworkCommand(
        UUID commandId,
        String type,
        String sourceProxyId,
        UUID targetPlayerUuid,
        Map<String, String> payload,
        Instant createdAt,
        Instant expiresAt
) {
    public NetworkCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String get(String key) {
        return payload.get(key);
    }

    public String getOrDefault(String key, String fallback) {
        return payload.getOrDefault(key, fallback);
    }
}
