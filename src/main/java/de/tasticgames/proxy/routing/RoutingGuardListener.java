package de.tasticgames.proxy.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerRegistryService;

import java.util.Map;
import java.util.Objects;

/**
 * Last line of defence: denies connections (from any source, e.g. /server) to servers
 * that are not accepting players, unless the player has the bypass permission.
 */
public final class RoutingGuardListener {

    private final ServerRegistryService registry;
    private final ProxyMessages messages;

    public RoutingGuardListener(ServerRegistryService registry, ProxyMessages messages) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        String destinationName = event.getResult().getServer().orElse(event.getOriginalServer()).getServerInfo().getName();
        NetworkServer destination = registry.find(destinationName).orElse(null);
        if (destination == null || destination.acceptingPlayers()) {
            return;
        }
        if (event.getPlayer().hasPermission(ProxyPermissions.ROUTING_BYPASS)) {
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (event.getPlayer().getCurrentServer().isPresent()) {
            messages.send(event.getPlayer(), "routing.unavailable", Map.of("server", destination.serverId()));
        }
    }
}
