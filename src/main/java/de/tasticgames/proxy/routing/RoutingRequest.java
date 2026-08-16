package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.server.ServerType;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Input of a routing decision.
 *
 * @param playerUuid     player (may be null for diagnostics)
 * @param targetType     desired server type (null when a concrete server is requested)
 * @param targetServerId concrete server (optional)
 * @param preferredRegion region to prefer (defaults to proxy region)
 * @param groupSize      number of players that must fit (1 for single transfers, party size for parties)
 * @param excludedServers servers that must not be chosen (e.g. the server the player was just kicked from)
 * @param currentServer  the player's current server (excluded unless {@code allowCurrent})
 * @param reason         transfer reason for diagnostics/telemetry
 */
public record RoutingRequest(
        UUID playerUuid,
        ServerType targetType,
        String targetServerId,
        String preferredRegion,
        int groupSize,
        Set<String> excludedServers,
        String currentServer,
        boolean allowCurrent,
        TransferReason reason
) {
    public RoutingRequest {
        if (targetType == null && targetServerId == null) {
            throw new IllegalArgumentException("targetType or targetServerId is required.");
        }
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be >= 1.");
        }
        excludedServers = excludedServers == null ? Set.of() : Set.copyOf(excludedServers);
        reason = reason == null ? TransferReason.COMMAND : reason;
    }

    public static RoutingRequest forType(UUID player, ServerType type, String currentServer, TransferReason reason) {
        return new RoutingRequest(player, type, null, null, 1, Set.of(), currentServer, false, reason);
    }

    public static RoutingRequest forServer(UUID player, String serverId, String currentServer, TransferReason reason) {
        return new RoutingRequest(player, null, serverId, null, 1, Set.of(), currentServer, false, reason);
    }

    public Optional<String> preferredRegionOptional() {
        return Optional.ofNullable(preferredRegion);
    }

    public RoutingRequest withGroupSize(int size) {
        return new RoutingRequest(playerUuid, targetType, targetServerId, preferredRegion, size, excludedServers,
                currentServer, allowCurrent, reason);
    }

    public RoutingRequest excluding(Set<String> more) {
        Set<String> merged = new java.util.HashSet<>(excludedServers);
        merged.addAll(more);
        return new RoutingRequest(playerUuid, targetType, targetServerId, preferredRegion, groupSize, merged,
                currentServer, allowCurrent, reason);
    }

    public String describeTarget() {
        return targetServerId != null ? targetServerId : Objects.toString(targetType);
    }
}
