package de.tasticgames.proxy.maintenance;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;

import java.util.Objects;

public final class MaintenancePingListener {

    private final MaintenanceService maintenanceService;

    public MaintenancePingListener(MaintenanceService maintenanceService) {
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (!maintenanceService.enabled()) {
            return;
        }
        ServerPing ping = event.getPing().asBuilder()
                .description(maintenanceService.motd())
                .clearSamplePlayers()
                .build();
        event.setPing(ping);
    }
}
