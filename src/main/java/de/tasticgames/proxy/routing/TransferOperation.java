package de.tasticgames.proxy.routing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracked transfer of one player. Mutable status guarded by synchronization.
 */
public final class TransferOperation {

    private final UUID transferId;
    private final UUID playerUuid;
    private final String playerName;
    private final String source;
    private final String target;
    private final TransferReason reason;
    private final String correlationId;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private TransferStatus status = TransferStatus.PLANNED;
    private String message = "";

    public TransferOperation(UUID transferId, UUID playerUuid, String playerName, String source, String target,
                             TransferReason reason, String correlationId) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.source = source == null ? "" : source;
        this.target = Objects.requireNonNull(target, "target");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.correlationId = correlationId == null ? transferId.toString() : correlationId;
        this.createdAt = Instant.now();
    }

    public UUID transferId() { return transferId; }
    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }
    public String source() { return source; }
    public String target() { return target; }
    public TransferReason reason() { return reason; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
    public synchronized Instant startedAt() { return startedAt; }
    public synchronized Instant finishedAt() { return finishedAt; }
    public synchronized TransferStatus status() { return status; }
    public synchronized String message() { return message; }

    synchronized void started() {
        status = TransferStatus.STARTED;
        startedAt = Instant.now();
    }

    /** @return false when the operation was already finished (idempotent completion) */
    synchronized boolean finish(TransferStatus newStatus, String newMessage) {
        if (status.terminal()) {
            return false;
        }
        status = newStatus;
        message = newMessage == null ? "" : newMessage;
        finishedAt = Instant.now();
        return true;
    }

    public synchronized long durationMillis() {
        Instant end = finishedAt == null ? Instant.now() : finishedAt;
        return java.time.Duration.between(createdAt, end).toMillis();
    }

    public TransferResult result() {
        return new TransferResult(transferId, status(), target, message());
    }
}
