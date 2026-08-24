package de.tasticgames.proxy.chat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.locale.ProxyLanguage;
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
 * ({@link PlayerChatEvent}) and puts its own rendered line on the command bus, which delivers it to every
 * proxy - this one included.
 * <p>
 * The message is deliberately <em>not</em> denied here. Since 1.19.1 a signed chat message cannot be
 * cancelled on the proxy: Velocity disconnects the player with "a proxy plugin caused an illegal protocol
 * state". The backend drops it instead - TasticCore cancels the chat event on every server
 * ({@code chat.handled-by-proxy} in core.yml), so the line still exists exactly once. Without TasticCore
 * (or with that switch off) players would see the message twice: once from the backend, once from here.
 * <p>
 * What a player typed is inserted as plain text ({@link ChatFormat}), so no one can inject colours, hover text
 * or click actions - colour codes are removed rather than rendered. Everything coloured in the line comes from
 * the server: the clan tag in front of the name and the rank colour of the name itself.
 */
public final class GlobalChatService implements ProxyService {

    /**
     * Exact numeric equivalent of Velocity's former PostOrder.LATE.
     * Higher priorities execute earlier.
     */
    private static final short LATE_EVENT_PRIORITY = -16_384;

    /** Payload keys of a {@link CommandTypes#CHAT_MESSAGE} command. */
    private static final String PLAYER = "player";
    private static final String UUID_KEY = "uuid";
    private static final String SERVER = "server";
    private static final String MESSAGE = "message";
    private static final String CLAN = "clan";
    private static final String NAME = "name";

    private final ProxyServer proxyServer;
    private final NetworkCommandBus bus;
    private final ProxyMessages messages;
    private final ChatIdentityProvider identity;
    private final Logger logger;
    private final boolean enabled;
    private final Duration cooldown;
    private final int maxLength;
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    /** Supplies what the network knows about a player: their coloured name and their clan tag. */
    public interface ChatIdentityProvider {

        /** The player's name including the colour of their rank, as MiniMessage. */
        String name(Player player);

        /** The clan tag shown in front of the name, as MiniMessage, or an empty string. */
        String clanTag(Player player);
    }

    public GlobalChatService(
            ProxyServer proxyServer,
            NetworkCommandBus bus,
            ProxyMessages messages,
            ChatIdentityProvider identity,
            boolean enabled,
            Duration cooldown,
            int maxLength,
            Logger logger
    ) {
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
            bus.subscribe(
                    CommandTypes.CHAT_MESSAGE,
                    this::handleRemoteMessage
            );
        }
    }

    @Override
    public void stop() {
        lastMessageAt.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    @Subscribe(priority = LATE_EVENT_PRIORITY)
    public void onChat(PlayerChatEvent event) {
        if (!enabled || !event.getResult().isAllowed()) {
            return;
        }

        Player player = event.getPlayer();
        String message = ChatFormat.sanitize(event.getMessage());

        if (ChatFormat.isBlank(message)) {
            return;
        }

        if (message.length() > maxLength) {
            message = message.substring(
                    0,
                    maxLength
            );
        }

        if (onCooldown(player)) {
            player.sendMessage(
                    messages.get(
                            player,
                            "chat.too-fast",
                            Map.of()
                    )
            );

            return;
        }

        String server = player.getCurrentServer()
                .map(connection ->
                        connection.getServerInfo().getName()
                )
                .orElse("");

        // no local rendering here: broadcast() delivers the command to this proxy as well, so rendering
        // it twice would show the line twice
        Map<String, String> payload =
                new LinkedHashMap<>();

        payload.put(
                UUID_KEY,
                player.getUniqueId().toString()
        );

        payload.put(
                PLAYER,
                player.getUsername()
        );

        payload.put(
                SERVER,
                server
        );

        payload.put(
                MESSAGE,
                message
        );

        payload.put(
                CLAN,
                identity.clanTag(player)
        );

        payload.put(
                NAME,
                identity.name(player)
        );

        bus.broadcast(
                        CommandTypes.CHAT_MESSAGE,
                        payload
                )
                .exceptionally(throwable -> {
                    // the message was delivered on this proxy; the other proxies simply miss it
                    logger.debug(
                            "Chat of {} could not be forwarded to the other proxies: {}",
                            player.getUsername(),
                            throwable.getMessage()
                    );

                    return null;
                });
    }

    /** Renders the line for every player on this proxy, each in their own language. */
    private void broadcastLocally(
            String clan,
            String name,
            String player,
            String server,
            String message
    ) {
        for (Player receiver : proxyServer.getAllPlayers()) {
            ProxyLanguage language =
                    messages.languageOf(receiver);

            String template =
                    messages.raw(
                            language,
                            "chat.format"
                    );

            Component line =
                    ChatFormat.render(
                            template,
                            clan,
                            name,
                            player,
                            ChatFormat.serverTag(server),
                            message
                    );

            receiver.sendMessage(line);
        }

        logger.info(
                "[chat] {}{}: {}",
                ChatFormat.serverTag(server).isEmpty()
                        ? ""
                        : "(" + ChatFormat.serverTag(server) + ") ",
                player,
                message
        );
    }

    /** A message another proxy accepted: rendered here so every player sees the same line. */
    private CompletableFuture<String> handleRemoteMessage(
            NetworkCommand command
    ) {
        String player =
                command.get(PLAYER);

        String message =
                command.get(MESSAGE);

        if (player == null || message == null) {
            return CompletableFuture.completedFuture(
                    "ignored"
            );
        }

        // clan tag and name colour were resolved by the proxy that accepted the message; this proxy
        // renders them as they arrived instead of looking the player up again
        broadcastLocally(
                command.getOrDefault(CLAN, ""),
                command.getOrDefault(NAME, player),
                player,
                command.get(SERVER),
                message
        );

        return CompletableFuture.completedFuture(
                "delivered"
        );
    }

    private boolean onCooldown(
            Player player
    ) {
        if (cooldown.isZero()
                || cooldown.isNegative()) {
            return false;
        }

        long now =
                System.currentTimeMillis();

        Long last =
                lastMessageAt.get(
                        player.getUniqueId()
                );

        if (last != null
                && now - last < cooldown.toMillis()) {
            return true;
        }

        lastMessageAt.put(
                player.getUniqueId(),
                now
        );

        return false;
    }

    public void forget(
            UUID player
    ) {
        lastMessageAt.remove(player);
    }
}
