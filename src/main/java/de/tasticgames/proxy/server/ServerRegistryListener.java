package de.tasticgames.proxy.server;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;

import java.util.Objects;

public final class ServerRegistryListener {

    private final ServerRegistryService serverRegistryService;

    public ServerRegistryListener(ServerRegistryService serverRegistryService) {
        this.serverRegistryService = Objects.requireNonNull(serverRegistryService, "serverRegistryService");
    }

    @Subscribe
    public void onServerRegistered(ServerRegisteredEvent event) {
        serverRegistryService.register(event.registeredServer());
    }

    @Subscribe
    public void onServerUnregistered(ServerUnregisteredEvent event) {
        serverRegistryService.unregister(event.unregisteredServer().getServerInfo().getName());
    }
}
