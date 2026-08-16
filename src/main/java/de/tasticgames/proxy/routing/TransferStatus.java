package de.tasticgames.proxy.routing;

public enum TransferStatus {
    PLANNED,
    STARTED,
    SUCCESS,
    ALREADY_CONNECTED,
    CANCELLED,
    TARGET_UNAVAILABLE,
    TARGET_FULL,
    NO_TARGET,
    DENIED,
    TIMEOUT,
    CONNECTION_FAILED,
    PLAYER_OFFLINE,
    ALREADY_TRANSFERRING,
    COOLDOWN,
    PARTIAL_FAILURE;

    public boolean successful() {
        return this == SUCCESS || this == ALREADY_CONNECTED;
    }

    public boolean terminal() {
        return this != PLANNED && this != STARTED;
    }
}
