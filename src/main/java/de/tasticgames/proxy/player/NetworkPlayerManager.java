package de.tasticgames.proxy.player;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.service.ProxyService;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry of local player runtime sessions.
 */
public final class NetworkPlayerManager implements ProxyService {

    private final ProxyIdentity identity;
    private final ConcurrentMap<UUID, NetworkPlayerSession> sessions = new ConcurrentHashMap<>();

    public NetworkPlayerManager(ProxyIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String id() {
        return "network-player-manager";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        clear();
    }

    /** Creates a fresh session; a stale session of the same player (reconnect) is replaced. */
    public NetworkPlayerSession connect(Player player) {
        Objects.requireNonNull(player, "player");
        NetworkPlayerSession session = new NetworkPlayerSession(player.getUniqueId(), player.getUsername(),
                identity.proxyId(), identity.region(), Instant.now());
        NetworkPlayerSession previous = sessions.put(player.getUniqueId(), session);
        if (previous != null) {
            previous.beginDisconnect();
            previous.disconnected();
        }
        return session;
    }

    /** @return the session and whether this was the first backend of the session (null when unknown). */
    public ServerChange serverConnected(UUID minecraftUuid, String serverName) {
        NetworkPlayerSession session = sessions.get(minecraftUuid);
        if (session == null) {
            return null;
        }
        String previous = session.currentServer().orElse(null);
        boolean first = session.serverConnected(serverName, Instant.now());
        return new ServerChange(session, first, previous);
    }

    public Optional<NetworkPlayerSession> disconnect(UUID minecraftUuid) {
        NetworkPlayerSession session = sessions.remove(minecraftUuid);
        if (session == null) {
            return Optional.empty();
        }
        session.beginDisconnect();
        session.disconnected();
        return Optional.of(session);
    }

    /** Idempotent disconnect for a specific session id (ignores stale events after a reconnect). */
    public Optional<NetworkPlayerSession> disconnect(UUID minecraftUuid, UUID sessionId) {
        NetworkPlayerSession current = sessions.get(minecraftUuid);
        if (current == null || !current.sessionId().equals(sessionId)) {
            return Optional.empty();
        }
        return disconnect(minecraftUuid);
    }

    public boolean beginTransfer(UUID minecraftUuid, UUID transferId) {
        NetworkPlayerSession session = sessions.get(minecraftUuid);
        return session != null && session.beginTransfer(transferId, Instant.now());
    }

    public void endTransfer(UUID minecraftUuid, UUID transferId) {
        NetworkPlayerSession session = sessions.get(minecraftUuid);
        if (session != null) {
            session.endTransfer(transferId);
        }
    }

    public Optional<NetworkPlayerSession> find(UUID minecraftUuid) {
        return Optional.ofNullable(sessions.get(minecraftUuid));
    }

    public NetworkPlayerSession require(UUID minecraftUuid) {
        return find(minecraftUuid).orElseThrow(() -> new IllegalStateException("Network player session does not exist: " + minecraftUuid));
    }

    public boolean online(UUID minecraftUuid) {
        return sessions.containsKey(minecraftUuid);
    }

    public Collection<NetworkPlayerSession> onlinePlayers() {
        return List.copyOf(sessions.values());
    }

    public int onlineCount() {
        return sessions.size();
    }

    public void clear() {
        sessions.values().forEach(s -> {
            s.beginDisconnect();
            s.disconnected();
        });
        sessions.clear();
    }

    public record ServerChange(NetworkPlayerSession session, boolean firstServer, String previousServer) {
    }
}
