package de.tasticgames.proxy.server;

import java.util.Locale;
import java.util.Optional;

/** Operator-controlled state, persisted centrally. Independent of technical health. */
public enum ServerAdminState {
    ONLINE,
    DRAINING,
    MAINTENANCE,
    OFFLINE;

    public static Optional<ServerAdminState> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ServerAdminState state : values()) {
            if (state.name().equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
