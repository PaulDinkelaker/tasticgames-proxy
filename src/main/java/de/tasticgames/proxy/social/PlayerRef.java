package de.tasticgames.proxy.social;

import java.util.Objects;
import java.util.UUID;

/** Resolved player identity (uuid + last-known name). */
public record PlayerRef(UUID uuid, String name) {
    public PlayerRef {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
