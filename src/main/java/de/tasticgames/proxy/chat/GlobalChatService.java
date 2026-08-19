package de.tasticgames.proxy.chat;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.service.ProxyService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One chat for the whole network: a message typed on any server reaches every player on every server, with the
 * same layout everywhere.
 * <p>
 * The proxy is the only place where this can be done once instead of per backend: it sees every message
 * ({@link PlayerChatEvent}), denies the pass-through and sends its own rendered line to all players on this
 * proxy plus – through the command bus – to the other proxies. The backends therefore never broadcast chat
 * themselves.
 * <p>
 * What a player typed is inserted as plain text ({@link ChatFormat}), so no one can inject colours, hover text
 * or click actions. The rank prefix and the network title come from the player's permissions/profile, not from
 * the message.
 */
public final class GlobalChatService implements ProxyService {

    /** Payload keys of a {@link CommandTypes#CHAT_MESSAGE} command. */
    private static final String PLAYER = "player";
    private static final String UUID_KEY = "uuid";
    private static final String SERVER = "server";
    private static final String MESSAGE = "message";
    private static final String PREFIX = "prefix";
    private static final String TITLE = "title";

    private final ProxyServer proxyServer;
    private final NetworkCommandBus bus;
    private final ProxyMessages messages;
    private final ChatIdentityProvider identity;
    private final Logger logger;
    private final boolean enabled;
    private final Duration cooldown;
    private final int maxLength;
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    /** Supplies what the network knows about a player: rank prefix and title. */
    public interface ChatIdentityProvider {

        /** MiniMessage prefix of the player's rank, or an empty string. */
        String prefix(Player player);

        /** The player's network title, or an empty string. */
        String title(Player player);

        /** Whether the player may use colour codes in chat. */
        boolean mayUseColors(Player player);
    }

    public GlobalChatService(ProxyServer proxyServer, NetworkCommandBus bus, ProxyMessages messages,
                             ChatIdentityProvider identity, boolean enabled, Duration cooldown, int maxLength,
                             Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.maxLength = maxLength;
    }

    @Override
    public String id() {
        return "global-chat";
    }

    @Override
    public void start() {
        if (enabled) {
            bus.subscribe(CommandTypes.CHAT_MESSAGE, this::handleRemoteMessage);
        }
    }

    @Override
    public void stop() {
        lastMessageAt.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    @Subscribe(order = PostOrder.LATE)
    public void onChat(PlayerChatEvent event) {
        if (!enabled || !event.getResult().isAllowed()) {
            return;
        }
        Player player = event.getPlayer();
        String message = ChatFormat.sanitize(event.getMessage());
        // the backend must not broadcast the same message a second time
        event.setResult(PlayerChatEvent.ChatResult.denied());

        if (ChatFormat.isBlank(message)) {
            return;
        }
        if (message.length() > maxLength) {
            message = message.substring(0, maxLength);
        }
        if (onCooldown(player)) {
            player.sendMessage(messages.get(player, "chat.too-fast", Map.of()));
            return;
        }
        String server = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        String prefix = identity.prefix(player);
        String title = identity.title(player);

        broadcastLocally(prefix, title, player.getUsername(), server, message, identity.mayUseColors(player));
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(UUID_KEY, player.getUniqueId().toString());
        payload.put(PLAYER, player.getUsername());
        payload.put(SERVER, server);
        payload.put(MESSAGE, message);
        payload.put(PREFIX, prefix);
        payload.put(TITLE, title);
        bus.broadcast(CommandTypes.CHAT_MESSAGE, payload).exceptionally(throwable -> {
            // the message was delivered on this proxy; the other proxies simply miss it
            logger.debug("Chat of {} could not be forwarded to the other proxies: {}", player.getUsername(),
                    throwable.getMessage());
            return null;
        });
    }

    /** Renders the line for every player on this proxy. */
    private void broadcastLocally(String prefix, String title, String player, String server, String message, boolean colors) {
        for (Player receiver : proxyServer.getAllPlayers()) {
            String template = messages.raw(messages.languageOf(receiver), "chat.format");
            Component line = ChatFormat.render(template, prefix, title, player, ChatFormat.serverTag(server), message, colors);
            receiver.sendMessage(line);
        }
        logger.info("[chat] {}{}: {}", ChatFormat.serverTag(server).isEmpty() ? "" : "(" + ChatFormat.serverTag(server) + ") ",
                player, message);
    }

    /** A message another proxy accepted: rendered here so every player sees the same line. */
    private CompletableFuture<String> handleRemoteMessage(NetworkCommand command) {
        String player = command.get(PLAYER);
        String message = command.get(MESSAGE);
        if (player == null || message == null) {
            return CompletableFuture.completedFuture("ignored");
        }
        // colours were already resolved by the proxy that accepted the message: a remote line is never
        // re-parsed with the sender's permissions
        broadcastLocally(command.get(PREFIX), command.get(TITLE), player, command.get(SERVER), message, false);
        return CompletableFuture.completedFuture("delivered");
    }

    private boolean onCooldown(Player player) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last != null && now - last < cooldown.toMillis()) {
            return true;
        }
        lastMessageAt.put(player.getUniqueId(), now);
        return false;
    }

    public void forget(UUID player) {
        lastMessageAt.remove(player);
    }
}
