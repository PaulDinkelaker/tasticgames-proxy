package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import de.tasticgames.proxy.config.ProxyPermissions;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.maintenance.MaintenanceService;
import de.tasticgames.proxy.maintenance.MaintenanceState;
import de.tasticgames.proxy.telemetry.TelemetryService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * /maintenance status|on [reason]|off|reason <text>|kick|refresh
 */
public final class MaintenanceCommand extends CommandSupport {

    private final MaintenanceService maintenanceService;

    public MaintenanceCommand(MaintenanceService maintenanceService, ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
    }

    @Override
    protected String commandName() {
        return "maintenance";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyPermissions.MAINTENANCE_ADMIN);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePermission(source, ProxyPermissions.MAINTENANCE_ADMIN)) {
            return;
        }
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "status" -> status(source);
            case "on", "enable" -> {
                String reason = join(args, 1);
                adminTelemetry(source, "on", reason);
                async(source, maintenanceService.update(true, reason.isBlank() ? null : reason, null, null, actor(source)),
                        state -> send(source, "maintenance.command.enabled", Map.of("reason", state.reason())));
            }
            case "off", "disable" -> {
                adminTelemetry(source, "off", "");
                async(source, maintenanceService.update(false, null, null, null, actor(source)),
                        state -> send(source, "maintenance.command.disabled"));
            }
            case "reason" -> {
                String reason = join(args, 1);
                MaintenanceState current = maintenanceService.state();
                adminTelemetry(source, "reason", reason);
                async(source, maintenanceService.update(current.enabled(), reason, current.expectedEndAt(), null, actor(source)),
                        state -> send(source, "maintenance.command.reason_set", Map.of("reason", state.reason())));
            }
            case "kick" -> {
                if (!maintenanceService.enabled()) {
                    send(source, "maintenance.command.not_enabled");
                    return;
                }
                adminTelemetry(source, "kick", "");
                int kicked = maintenanceService.kickNonBypassPlayers();
                send(source, "maintenance.command.kicked", Map.of("count", kicked));
            }
            case "refresh" -> {
                maintenanceService.refresh();
                send(source, "maintenance.command.refreshed");
            }
            default -> send(source, "maintenance.command.usage");
        }
    }

    private void status(CommandSource source) {
        MaintenanceState state = maintenanceService.state();
        send(source, "maintenance.command.status", Map.of(
                "state", state.enabled() ? "ENABLED" : "DISABLED",
                "reason", state.reason().isBlank() ? "-" : state.reason(),
                "changedBy", state.changedBy(),
                "changedAt", ago(state.changedAt()),
                "source", maintenanceService.centralLoaded() ? "central" : "last-known-good",
                "kick", state.kickOnlinePlayers()));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return filter(List.of("status", "on", "off", "reason", "kick", "refresh"), args.length == 0 ? "" : args[0]);
        }
        return List.of();
    }
}
