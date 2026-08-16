package de.tasticgames.proxy.routing;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TransferResult(
        UUID transferId,
        TransferStatus status,
        String destination,
        String message
) {
    public TransferResult {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        destination = destination == null ? "" : destination;
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status.successful();
    }

    public Optional<String> failureMessage() {
        return message.isBlank() ? Optional.empty() : Optional.of(message);
    }
}
