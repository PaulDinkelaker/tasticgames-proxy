package de.tasticgames.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.social.friend.FriendService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.Throwables;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shared command plumbing: localized messages, permission checks, async result handling
 * without stack traces for players, admin command telemetry.
 */
public abstract class CommandSupport implements SimpleCommand {

    protected final ProxyMessages messages;
    protected final TelemetryService telemetry;
    protected final Logger logger;

    protected CommandSupport(ProxyMessages messages, TelemetryService telemetry, Logger logger) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    protected boolean requirePermission(CommandSource source, String permission) {
        if (source.hasPermission(permission)) {
            return true;
        }
        messages.send(source, "command.no_permission");
        return false;
    }

    protected boolean requirePlayer(CommandSource source) {
        if (source instanceof Player) {
            return true;
        }
        messages.send(source, "command.players_only");
        return false;
    }

    private static volatile long lastUnavailableLogAt;

    /**
     * Player-facing "unavailable" plus a throttled ERROR for operators: the social/network commands are
     * refused because the API integration is disabled or degraded – silence here is what makes testers
     * report "everything is temporarily unavailable" without a hint in the console.
     */
    protected void unavailable(CommandSource source, String reason) {
        send(source, "social.unavailable");
        long now = System.currentTimeMillis();
        if (now - lastUnavailableLogAt > 60_000) {
            lastUnavailableLogAt = now;
            logger.error("/{} refused for {}: {} (check api.enabled, api.authentication.api-key / TASTIC_API_KEY and the API log; /tasticproxy status shows details).",
                    commandName(), describe(source), reason);
        }
    }

    protected void send(CommandSource source, String key) {
        messages.send(source, key);
    }

    protected void send(CommandSource source, String key, Map<String, ?> placeholders) {
        messages.send(source, key, placeholders);
    }

    protected Component raw(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    /** Runs the async action; failures are reported to the source without stack traces. */
    protected <T> void async(CommandSource source, CompletableFuture<T> future, Consumer<T> onSuccess) {
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                Throwable cause = Throwables.unwrap(throwable);
                if (cause instanceof FriendService.RateLimitedException) {
                    send(source, "social.rate_limited");
                } else if (cause instanceof ProxyApiClient.ApiUnavailableException) {
                    send(source, "social.unavailable");
                } else if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 404) {
                    send(source, "common.player_not_found");
                } else {
                    logger.warn("Command '{}' failed for {}: {}", commandName(), describe(source), Throwables.rootMessage(cause));
                    send(source, "social.error");
                }
                return;
            }
            try {
                onSuccess.accept(result);
            } catch (RuntimeException e) {
                logger.error("Command '{}' result handling failed: {}", commandName(), Throwables.rootMessage(e), e);
                send(source, "social.error");
            }
        });
    }

    protected void adminTelemetry(CommandSource source, String action, String detail) {
        telemetry.publish(telemetry.event(TelemetryTypes.ADMIN_COMMAND)
                .player(source instanceof Player p ? p.getUniqueId() : null)
                .attribute("command", commandName()).attribute("action", action).attribute("detail", detail).build());
        logger.info("Admin command /{} {} by {}{}", commandName(), action, describe(source), detail == null || detail.isBlank() ? "" : " – " + detail);
    }

    protected static String describe(CommandSource source) {
        return source instanceof Player p ? p.getUsername() : "CONSOLE";
    }

    protected static String actor(CommandSource source) {
        return source instanceof Player p ? p.getUsername() : "console";
    }

    protected static String join(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
    }

    protected static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    protected static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        return sb.toString();
    }

    protected static String ago(Instant instant) {
        if (instant == null) {
            return "never";
        }
        return formatDuration(Duration.between(instant, Instant.now())) + " ago";
    }

    protected abstract String commandName();
}
