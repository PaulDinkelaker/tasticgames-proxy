package de.tasticgames.proxy.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.Objects;

public final class FallbackListener {

    private final FallbackService fallbackService;
    private final Logger logger;

    public FallbackListener(FallbackService fallbackService, Logger logger) {
        this.fallbackService = Objects.requireNonNull(fallbackService, "fallbackService");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String failedServer = event.getServer().getServerInfo().getName().toLowerCase(java.util.Locale.ROOT);
        String reason = event.getServerKickReason().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).orElse("");
        logger.warn("Player {} was kicked from {}{}{}.", event.getPlayer().getUsername(), failedServer,
                event.kickedDuringServerConnect() ? " during connect" : "", reason.isBlank() ? "" : ": " + reason);
        event.setResult(fallbackService.decide(event.getPlayer(), failedServer,
                event.getServerKickReason().orElse(null), event.kickedDuringServerConnect()));
    }
}
