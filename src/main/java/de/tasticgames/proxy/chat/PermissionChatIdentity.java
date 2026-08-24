package de.tasticgames.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.locale.ProxyLanguage;
import de.tasticgames.proxy.locale.ProxyMessages;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Wer jemand im Chat ist: der Rang bestimmt die Farbe des Namens, der Clan das Kürzel davor.
 *
 * <p>Der Rang kommt aus den Rechten – der erste Rang aus {@code chat.ranks}, dessen Permission
 * {@code tasticgames.chat.rank.<rang>} der Spieler hat, gewinnt, und {@code chat.name-color.<rang>}
 * färbt seinen Namen. Das braucht bewusst keine LuckPerms-API auf dem Proxy: eine Rechteabfrage
 * genügt, und LuckPerms (oder jedes andere Rechteplugin) beantwortet sie. Ränge und Farben stehen in
 * den Nachrichtendateien, ein Betreiber ändert sie also ohne Neubau.</p>
 */
public final class PermissionChatIdentity implements GlobalChatService.ChatIdentityProvider {

    private static final String RANK_PERMISSION = "tasticgames.chat.rank.";
    /** Farbe für alle ohne besonderen Rang. */
    private static final String DEFAULT_COLOR_KEY = "chat.name-color.default";

    private final ProxyMessages messages;
    private final ClanTagService clans;

    public PermissionChatIdentity(ProxyMessages messages, ClanTagService clans) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clans = Objects.requireNonNull(clans, "clans");
    }

    /** Der Name in der Farbe seines Ranges, als fertige MiniMessage. */
    @Override
    public String name(Player player) {
        return colorOf(player) + player.getUsername();
    }

    /** Das Clan-Kürzel vor dem Namen, oder ein leerer Text. */
    @Override
    public String clanTag(Player player) {
        Optional<ClanTag> tag = clans.tagOf(player);
        if (tag.isEmpty()) {
            return "";
        }
        String template = messages.raw(ProxyLanguage.ENGLISH, "chat.clan-tag");
        return template.startsWith("<red>[chat.clan-tag") ? "" : tag.get().render(template);
    }

    /** Die MiniMessage-Farbe des höchsten Ranges, den der Spieler hat. */
    private String colorOf(Player player) {
        for (String rank : ranks()) {
            if (player.hasPermission(RANK_PERMISSION + rank)) {
                String color = messages.raw(ProxyLanguage.ENGLISH, "chat.name-color." + rank);
                if (!color.startsWith("<red>[chat.name-color.")) {
                    return color;
                }
            }
        }
        String fallback = messages.raw(ProxyLanguage.ENGLISH, DEFAULT_COLOR_KEY);
        return fallback.startsWith("<red>[" + DEFAULT_COLOR_KEY) ? "<gray>" : fallback;
    }

    /** Ränge in absteigender Reihenfolge. */
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
