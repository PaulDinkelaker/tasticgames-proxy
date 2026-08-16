package de.tasticgames.proxy.bus;

import de.tasticgames.proxy.service.ProxyService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-proxy coordination bus. The current implementation is API/MySQL backed
 * ({@link ApiNetworkCommandBus}); the interface allows a Redis/Kafka transport later.
 */
public interface NetworkCommandBus extends ProxyService {

    /** Result of a submission: which proxies received it (may include this proxy). */
    record Submission(UUID commandId, boolean duplicate, java.util.List<String> deliveredTo, boolean undeliverable, boolean handledLocally) {
    }

    void subscribe(String type, NetworkCommandHandler handler);

    CompletableFuture<Submission> broadcast(String type, Map<String, String> payload);

    CompletableFuture<Submission> sendToProxy(String proxyId, String type, Map<String, String> payload);

    /** Delivers to the proxy the player is currently on (locally when the player is here). */
    CompletableFuture<Submission> sendToPlayer(UUID playerUuid, String type, Map<String, String> payload);

    boolean available();

    int pendingLocalCommands();

    long processedCount();

    long failedCount();
}
