package de.tasticgames.proxy.bus;

import java.util.concurrent.CompletableFuture;

/**
 * Handles one command type. Must be idempotent (a command may be redelivered when the
 * acknowledgement was lost). Returns a result string used for the acknowledgement.
 */
@FunctionalInterface
public interface NetworkCommandHandler {

    CompletableFuture<String> handle(NetworkCommand command);
}
