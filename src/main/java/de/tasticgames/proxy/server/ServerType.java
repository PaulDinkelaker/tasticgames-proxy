package de.tasticgames.proxy.server;

import java.util.Locale;
import java.util.Optional;

public enum ServerType {
    LOBBY,
    SURVIVAL,
    CREATIVE,
    DUELS,
    OTHER;

    public static Optional<ServerType> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ServerType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Best-effort inference from a Velocity server name when no config entry exists. */
    public static ServerType infer(String serverName) {
        String name = serverName.toLowerCase(Locale.ROOT);
        if (name.startsWith("lobby") || name.startsWith("hub")) return LOBBY;
        if (name.startsWith("survival") || name.startsWith("smp")) return SURVIVAL;
        if (name.startsWith("creative") || name.startsWith("build")) return CREATIVE;
        if (name.startsWith("duel")) return DUELS;
        return OTHER;
    }
}
