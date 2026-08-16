package de.tasticgames.proxy.server;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;

public final class DrainListener {

    private final DrainService drainService;

    public DrainListener(DrainService drainService) {
        this.drainService = Objects.requireNonNull(drainService, "drainService");
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        RegisteredServer previous = event.getPreviousServer();
        if (previous != null) {
            drainService.checkServer(previous.getServerInfo().getName());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(connection ->
                drainService.checkServer(connection.getServerInfo().getName()));
    }
}
