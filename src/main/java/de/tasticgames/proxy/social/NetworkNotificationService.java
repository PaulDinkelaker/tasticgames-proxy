package de.tasticgames.proxy.social;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.service.ProxyService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central network notification service: localized messages to players anywhere on the
 * network. Local players are messaged directly; remote players via the command bus.
 */
public final class NetworkNotificationService implements ProxyService {

    private final ProxyServer proxyServer;
    private final NetworkCommandBus bus;
    private final ProxyMessages messages;
    private final Logger logger;

    public NetworkNotificationService(ProxyServer proxyServer, NetworkCommandBus bus, ProxyMessages messages, Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "network-notification-service";
    }

    @Override
    public void start() {
        bus.subscribe(CommandTypes.PLAYER_MESSAGE, this::handleMessageCommand);
    }

    @Override
    public void stop() {
    }

    /** Sends a localized message (by key) to a player wherever they are. */
    public CompletableFuture<Boolean> notify(UUID player, String key, Map<String, ?> placeholders) {
        Player local = proxyServer.getPlayer(player).orElse(null);
        if (local != null) {
            local.sendMessage(messages.get(local, key, placeholders));
            return CompletableFuture.completedFuture(true);
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("key", key);
        placeholders.forEach((k, v) -> payload.put("p." + k, String.valueOf(v)));
        return bus.sendToPlayer(player, CommandTypes.PLAYER_MESSAGE, payload)
                .thenApply(submission -> !submission.undeliverable())
                .exceptionally(throwable -> {
                    logger.debug("Notification {} to {} could not be delivered.", key, player);
                    return false;
                });
    }

    public CompletableFuture<Boolean> notify(UUID player, String key) {
        return notify(player, key, Map.of());
    }

    public void notifyAll(Collection<UUID> players, String key, Map<String, ?> placeholders) {
        for (UUID player : players) {
            notify(player, key, placeholders);
        }
    }

    /** Sends a pre-built component to a local player only (used for click-actions etc.). */
    public boolean sendLocal(UUID player, Component component) {
        Player local = proxyServer.getPlayer(player).orElse(null);
        if (local == null) {
            return false;
        }
        local.sendMessage(component);
        return true;
    }

    private CompletableFuture<String> handleMessageCommand(NetworkCommand command) {
        UUID target = command.targetPlayerUuid();
        String key = command.get("key");
        if (target == null || key == null) {
            return CompletableFuture.completedFuture("ignored: no target/key");
        }
        Player player = proxyServer.getPlayer(target).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture("player not here");
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        command.payload().forEach((k, v) -> {
            if (k.startsWith("p.")) {
                placeholders.put(k.substring(2), v);
            }
        });
        player.sendMessage(messages.get(player, key, placeholders));
        return CompletableFuture.completedFuture("delivered");
    }
}
