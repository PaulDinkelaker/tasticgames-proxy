package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.routing.TransferService;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.telemetry.TelemetryService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * /lobby (aliases /hub, /l): routes the player to the best lobby.
 */
public final class LobbyCommand extends CommandSupport {

    private final TransferService transferService;

    public LobbyCommand(TransferService transferService, ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        super(messages, telemetry, logger);
        this.transferService = Objects.requireNonNull(transferService);
    }

    @Override
    protected String commandName() {
        return "lobby";
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!requirePlayer(source)) {
            return;
        }
        Player player = (Player) source;
        async(source, transferService.transferToType(player, ServerType.LOBBY, TransferReason.COMMAND), result -> {
            switch (result.status()) {
                case SUCCESS -> { }
                case ALREADY_CONNECTED -> send(source, "transfer.already_there", Map.of("target", "Lobby"));
                case COOLDOWN, ALREADY_TRANSFERRING -> send(source, "transfer.wait");
                default -> send(source, "transfer.failed", Map.of("target", "Lobby", "status", result.status().name()));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
