package de.tasticgames.proxy.access;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;

import java.util.Objects;

public final class AlphaAccessListener {

    private final AlphaAccessService alphaAccessService;
    private final TelemetryService telemetry;

    public AlphaAccessListener(AlphaAccessService alphaAccessService, TelemetryService telemetry) {
        this.alphaAccessService = Objects.requireNonNull(alphaAccessService, "alphaAccessService");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed() || alphaAccessService.mayJoin(event.getPlayer())) {
            return;
        }
        event.setResult(LoginEvent.ComponentResult.denied(alphaAccessService.disconnectMessage(event.getPlayer())));
        telemetry.publish(telemetry.event(TelemetryTypes.ALPHA_JOIN_DENIED)
                .player(event.getPlayer().getUniqueId()).outcome("DENIED")
                .attribute("stateKnown", alphaAccessService.stateKnown()).build());
    }
}
