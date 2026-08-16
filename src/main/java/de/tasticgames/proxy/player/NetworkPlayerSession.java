package de.tasticgames.proxy.player;

import de.tasticgames.proxy.util.Ids;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local runtime state of one player on this proxy. The persistent truth (presence,
 * sessions) lives in the API; this object tracks what the proxy needs for routing.
 */
public final class NetworkPlayerSession {

    private final UUID minecraftUuid;
    private final String username;
    private final UUID sessionId;
    private final String proxyId;
    private final String region;
    private final Instant connectedAt;

    private NetworkPlayerState state = NetworkPlayerState.CONNECTING;
    private String currentServer;
    private String previousServer;
    private Instant lastServerChangeAt;
    private UUID pendingTransferId;
    private Instant lastTransferAt = Instant.EPOCH;
    private long pingSnapshot = -1;
    private Instant lastPresenceHeartbeatAt = Instant.EPOCH;

    public NetworkPlayerSession(UUID minecraftUuid, String username, String proxyId, String region, Instant connectedAt) {
        this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (!Ids.isUsername(username)) {
            throw new IllegalArgumentException("Invalid Minecraft username: " + username);
        }
        this.username = username.trim();
        this.sessionId = UUID.randomUUID();
        this.proxyId = Objects.requireNonNull(proxyId, "proxyId");
        this.region = Objects.requireNonNull(region, "region");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
    }

    public UUID minecraftUuid() { return minecraftUuid; }
    public String username() { return username; }
    public UUID sessionId() { return sessionId; }
    public String proxyId() { return proxyId; }
    public String region() { return region; }
    public Instant connectedAt() { return connectedAt; }

    public synchronized NetworkPlayerState state() { return state; }
    public synchronized Optional<String> currentServer() { return Optional.ofNullable(currentServer); }
    public synchronized Optional<String> previousServer() { return Optional.ofNullable(previousServer); }
    public synchronized Optional<Instant> lastServerChangeAt() { return Optional.ofNullable(lastServerChangeAt); }
    public synchronized Optional<UUID> pendingTransferId() { return Optional.ofNullable(pendingTransferId); }
    public synchronized Instant lastTransferAt() { return lastTransferAt; }
    public synchronized long pingSnapshot() { return pingSnapshot; }
    public synchronized Instant lastPresenceHeartbeatAt() { return lastPresenceHeartbeatAt; }

    public synchronized boolean connectedToServer() {
        return currentServer != null && (state == NetworkPlayerState.CONNECTED || state == NetworkPlayerState.TRANSFERRING);
    }

    /** @return true when this is the first backend connection of the session */
    synchronized boolean serverConnected(String serverName, Instant at) {
        if (state == NetworkPlayerState.DISCONNECTING || state == NetworkPlayerState.DISCONNECTED) {
            throw new IllegalStateException("Cannot connect a closed session to a server: " + minecraftUuid);
        }
        String normalized = Ids.serverId(serverName);
        boolean first = currentServer == null;
        previousServer = currentServer;
        currentServer = normalized;
        lastServerChangeAt = at;
        state = NetworkPlayerState.CONNECTED;
        pendingTransferId = null;
        return first;
    }

    synchronized boolean beginTransfer(UUID transferId, Instant at) {
        if (state != NetworkPlayerState.CONNECTED && state != NetworkPlayerState.CONNECTING) {
            return false;
        }
        if (pendingTransferId != null) {
            return false;
        }
        pendingTransferId = transferId;
        lastTransferAt = at;
        if (state == NetworkPlayerState.CONNECTED) {
            state = NetworkPlayerState.TRANSFERRING;
        }
        return true;
    }

    synchronized void endTransfer(UUID transferId) {
        if (transferId.equals(pendingTransferId)) {
            pendingTransferId = null;
            if (state == NetworkPlayerState.TRANSFERRING) {
                state = NetworkPlayerState.CONNECTED;
            }
        }
    }

    synchronized void beginDisconnect() {
        state = NetworkPlayerState.DISCONNECTING;
        pendingTransferId = null;
    }

    synchronized void disconnected() {
        state = NetworkPlayerState.DISCONNECTED;
    }

    public synchronized void updatePing(long ping) {
        this.pingSnapshot = ping;
    }

    public synchronized void presenceHeartbeatSent(Instant at) {
        this.lastPresenceHeartbeatAt = at;
    }
}
