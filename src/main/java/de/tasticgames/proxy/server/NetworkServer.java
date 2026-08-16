package de.tasticgames.proxy.server;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tasticgames.proxy.util.Ids;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Local view of a network backend server: static metadata, central administrative state
 * (last-known-good copy) and locally observed health. Thread-safe via synchronized access.
 */
public final class NetworkServer {

    private final String serverId;
    private final RegisteredServer registeredServer;

    private ServerType type;
    private String region;
    private int capacity;
    private int weight;
    private List<String> tags;

    private ServerAdminState adminState = ServerAdminState.ONLINE;
    private Instant stateChangedAt = Instant.now();
    private String stateChangedBy = "local";
    private String stateReason = "registered";
    private long stateVersion = -1;

    private ServerHealthState health = ServerHealthState.UNKNOWN;
    private Instant healthChangedAt = Instant.now();
    private Instant lastHealthCheckAt;
    private Instant lastHealthyAt;
    private long latencyMillis = -1;
    private int consecutiveSuccesses;
    private int consecutiveFailures;
    private String lastFailureMessage = "";
    private int remotePlayerCount;
    private Instant remoteUpdatedAt;

    public NetworkServer(RegisteredServer registeredServer, ServerType type, String region, int capacity, int weight,
                         List<String> tags) {
        this.registeredServer = Objects.requireNonNull(registeredServer, "registeredServer");
        this.serverId = Ids.serverId(registeredServer.getServerInfo().getName());
        this.type = Objects.requireNonNull(type, "type");
        this.region = Objects.requireNonNull(region, "region");
        this.capacity = capacity;
        this.weight = weight;
        this.tags = List.copyOf(tags);
    }

    public String serverId() { return serverId; }
    public String name() { return serverId; }
    public RegisteredServer registeredServer() { return registeredServer; }
    public String address() { return registeredServer.getServerInfo().getAddress().toString(); }

    public synchronized ServerType type() { return type; }
    public synchronized String region() { return region; }
    public synchronized int capacity() { return capacity; }
    public synchronized int weight() { return weight; }
    public synchronized List<String> tags() { return tags; }

    public synchronized void updateMetadata(ServerType type, String region, int capacity, int weight, List<String> tags) {
        this.type = type;
        this.region = region;
        this.capacity = capacity;
        this.weight = weight;
        this.tags = List.copyOf(tags);
    }

    // ------------------------------------------------------------------ admin state

    public synchronized ServerAdminState adminState() { return adminState; }
    public synchronized Instant stateChangedAt() { return stateChangedAt; }
    public synchronized String stateChangedBy() { return stateChangedBy; }
    public synchronized String stateReason() { return stateReason; }
    public synchronized long stateVersion() { return stateVersion; }

    /** @return true when the state actually changed */
    public synchronized boolean applyAdminState(ServerAdminState newState, String changedBy, String reason,
                                                Instant changedAt, long version) {
        boolean changed = adminState != newState;
        adminState = newState;
        stateChangedBy = changedBy == null ? "unknown" : changedBy;
        stateReason = reason == null ? "" : reason;
        stateChangedAt = changedAt == null ? Instant.now() : changedAt;
        stateVersion = version;
        return changed;
    }

    public synchronized boolean draining() { return adminState == ServerAdminState.DRAINING; }
    public synchronized boolean maintenance() { return adminState == ServerAdminState.MAINTENANCE; }
    public synchronized boolean offline() { return adminState == ServerAdminState.OFFLINE; }

    /** Administratively online AND not known to be unreachable. */
    public synchronized boolean acceptingPlayers() {
        return adminState == ServerAdminState.ONLINE && health != ServerHealthState.UNREACHABLE;
    }

    // ------------------------------------------------------------------ health

    public synchronized ServerHealthState health() { return health; }
    public synchronized Instant healthChangedAt() { return healthChangedAt; }
    public synchronized Instant lastHealthCheckAt() { return lastHealthCheckAt; }
    public synchronized Instant lastHealthyAt() { return lastHealthyAt; }
    public synchronized long latencyMillis() { return latencyMillis; }
    public synchronized int consecutiveSuccesses() { return consecutiveSuccesses; }
    public synchronized int consecutiveFailures() { return consecutiveFailures; }
    public synchronized String lastFailureMessage() { return lastFailureMessage; }
    public synchronized int remotePlayerCount() { return remotePlayerCount; }
    public synchronized Instant remoteUpdatedAt() { return remoteUpdatedAt; }

    public synchronized void recordPingSuccess(long latency, int reportedPlayers, Instant at) {
        consecutiveSuccesses++;
        consecutiveFailures = 0;
        latencyMillis = latency;
        lastHealthCheckAt = at;
        lastHealthyAt = at;
        lastFailureMessage = "";
        remotePlayerCount = Math.max(reportedPlayers, localPlayerCount());
        remoteUpdatedAt = at;
    }

    public synchronized void recordPingFailure(String message, Instant at) {
        consecutiveFailures++;
        consecutiveSuccesses = 0;
        lastHealthCheckAt = at;
        lastFailureMessage = message == null ? "" : message;
    }

    /** @return the previous health when it changed, otherwise null. */
    public synchronized ServerHealthState applyHealth(ServerHealthState newHealth, Instant at) {
        if (health == newHealth) {
            return null;
        }
        ServerHealthState previous = health;
        health = newHealth;
        healthChangedAt = at;
        return previous;
    }

    public synchronized void applyRemotePlayerCount(int count, Instant at) {
        remotePlayerCount = count;
        remoteUpdatedAt = at;
    }

    // ------------------------------------------------------------------ players

    /** Players connected through THIS proxy. */
    public int localPlayerCount() {
        return registeredServer.getPlayersConnected().size();
    }

    /** Best known network-wide player count (max of local and last remote observation). */
    public synchronized int knownPlayerCount() {
        return Math.max(localPlayerCount(), remotePlayerCount);
    }

    public synchronized int freeSlots() {
        return Math.max(0, capacity - knownPlayerCount());
    }

    public boolean empty() {
        return localPlayerCount() == 0 && remotePlayerCount() == 0;
    }

    @Override
    public String toString() {
        return "NetworkServer[" + serverId + " " + type + " " + region + " " + adminState + "/" + health + "]";
    }
}
