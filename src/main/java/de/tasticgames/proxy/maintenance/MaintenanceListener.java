package de.tasticgames.proxy.maintenance;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;

import java.util.Objects;

public final class MaintenanceListener {

    private final MaintenanceService maintenanceService;
    private final TelemetryService telemetry;

    public MaintenanceListener(MaintenanceService maintenanceService, TelemetryService telemetry) {
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed() || maintenanceService.mayJoin(event.getPlayer())) {
            return;
        }
        event.setResult(LoginEvent.ComponentResult.denied(maintenanceService.disconnectMessage(event.getPlayer())));
        telemetry.publish(telemetry.event(TelemetryTypes.MAINTENANCE_JOIN_DENIED)
                .player(event.getPlayer().getUniqueId()).outcome("DENIED").build());
    }
}
