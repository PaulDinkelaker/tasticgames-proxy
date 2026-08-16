package de.tasticgames.proxy.maintenance;

import java.time.Instant;
import java.util.Objects;

/** Immutable maintenance state snapshot (central source of truth, cached locally). */
public record MaintenanceState(
        boolean enabled,
        String reason,
        Instant expectedEndAt,
        boolean kickOnlinePlayers,
        Instant changedAt,
        String changedBy,
        long version
) {
    public MaintenanceState {
        reason = reason == null ? "" : reason;
        changedBy = changedBy == null ? "unknown" : changedBy;
        changedAt = changedAt == null ? Instant.EPOCH : changedAt;
    }

    public static MaintenanceState local(boolean enabled, boolean kickOnlinePlayers) {
        return new MaintenanceState(enabled, "", null, kickOnlinePlayers, Instant.now(), "local-config", -1);
    }

    public boolean sameAs(MaintenanceState other) {
        return other != null && enabled == other.enabled && Objects.equals(reason, other.reason)
                && Objects.equals(expectedEndAt, other.expectedEndAt) && kickOnlinePlayers == other.kickOnlinePlayers;
    }
}
