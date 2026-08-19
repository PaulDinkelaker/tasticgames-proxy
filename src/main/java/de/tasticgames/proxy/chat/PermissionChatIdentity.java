package de.tasticgames.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.locale.ProxyLanguage;
import de.tasticgames.proxy.locale.ProxyMessages;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Chat identity from permissions: the first rank of {@code chat.ranks} whose permission the player has wins,
 * and its {@code chat.prefix.<rank>} is put in front of the name.
 * <p>
 * This deliberately needs no LuckPerms API on the proxy – a permission check is enough, and LuckPerms (or any
 * other permission plugin) answers it. Ranks and prefixes live in the proxy message files, so an operator
 * changes them without a rebuild.
 * <p>
 * Titles are not wired yet: {@link #title(Player)} returns an empty string until the network title system
 * exists, and the {@code <title>} placeholder of the chat format then simply renders as nothing.
 */
public final class PermissionChatIdentity implements GlobalChatService.ChatIdentityProvider {

    /** Permission that allows colour codes in chat. */
    public static final String COLOR_PERMISSION = "tasticgames.chat.color";
    private static final String RANK_PERMISSION = "tasticgames.chat.rank.";

    private final ProxyMessages messages;

    public PermissionChatIdentity(ProxyMessages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public String prefix(Player player) {
        for (String rank : ranks()) {
            if (player.hasPermission(RANK_PERMISSION + rank)) {
                String prefix = messages.raw(ProxyLanguage.ENGLISH, "chat.prefix." + rank);
                return prefix.startsWith("<red>[chat.prefix.") ? "" : prefix;
            }
        }
        return "";
    }

    @Override
    public String title(Player player) {
        return ""; // network titles are not implemented yet – see docs/tasticproxy-architecture.md
    }

    @Override
    public boolean mayUseColors(Player player) {
        return player.hasPermission(COLOR_PERMISSION);
    }

    /** Ranks in priority order, highest first. */
    private List<String> ranks() {
        String raw = messages.raw(ProxyLanguage.ENGLISH, "chat.ranks");
        if (raw.startsWith("<red>[")) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
