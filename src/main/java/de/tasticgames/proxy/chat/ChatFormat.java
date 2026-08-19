package de.tasticgames.proxy.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;
import java.util.Objects;

/**
 * Builds one chat line from a MiniMessage template. Pure string work so the escaping rules can be tested
 * without a proxy.
 * <p>
 * What a player types is never parsed as MiniMessage: it goes in as plain text through a placeholder, so
 * {@code <red>} in a message stays the four characters a player typed and nobody can inject click actions,
 * hover text or colours into the chat. Colour codes are a permission ({@code tasticgames.chat.color}) and are
 * translated from the legacy {@code &a} form, which is what players know.
 */
public final class ChatFormat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Legacy colour codes players type; translated to MiniMessage tags when they may use them. */
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";

    private ChatFormat() {
    }

    /**
     * Renders one chat line.
     *
     * @param template MiniMessage template with {@code <player>}, {@code <message>}, {@code <server>},
     *                 {@code <prefix>} and {@code <title>} placeholders
     * @param prefix   rank prefix (already MiniMessage/legacy formatted, comes from the server, not a player)
     * @param title    the player's network title, or an empty string
     * @param player   the player's name
     * @param server   display name of the server the message came from
     * @param message  raw text the player typed
     * @param colors   whether the player may use colour codes
     */
    public static Component render(String template, String prefix, String title, String player, String server,
                                   String message, boolean colors) {
        Objects.requireNonNull(template, "template");
        String text = sanitize(message);
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("prefix", prefix == null ? "" : prefix),
                Placeholder.parsed("title", title == null ? "" : title),
                Placeholder.unparsed("player", player == null ? "" : player),
                Placeholder.unparsed("server", server == null ? "" : server),
                colors ? Placeholder.parsed("message", legacyToMiniMessage(text)) : Placeholder.unparsed("message", text));
        return MINI.deserialize(template, resolver);
    }

    /**
     * Removes what must never reach other players: section signs (a client would render them as colours),
     * control characters and trailing whitespace. The message itself is kept as typed.
     */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '§') {
                // the sign and the code behind it go together, otherwise "§chello" would leave "chello"
                if (i + 1 < message.length() && LEGACY_CODES.indexOf(Character.toLowerCase(message.charAt(i + 1))) >= 0) {
                    i++;
                }
                continue;
            }
            if (c == '\n' || c == '\r' || (c < ' ' && c != '\t')) {
                continue;
            }
            clean.append(c);
        }
        return clean.toString().strip();
    }

    /** {@code &a} becomes {@code <green>}: players type the legacy codes, MiniMessage renders them. */
    static String legacyToMiniMessage(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c != '&' || i + 1 >= message.length()) {
                out.append(c);
                continue;
            }
            char code = Character.toLowerCase(message.charAt(i + 1));
            if (LEGACY_CODES.indexOf(code) < 0) {
                out.append(c);
                continue;
            }
            out.append('<').append(tag(code)).append('>');
            i++;
        }
        return out.toString();
    }

    private static String tag(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            default -> "reset";
        };
    }

    /** Whether the message is worth sending at all (empty after sanitising = nothing was typed). */
    public static boolean isBlank(String message) {
        return sanitize(message).isEmpty();
    }

    /** Short tag of a server id for the chat line: {@code survival-1} becomes {@code SURVIVAL}. */
    public static String serverTag(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return "";
        }
        String name = serverId.trim();
        int dash = name.indexOf('-');
        if (dash > 0) {
            name = name.substring(0, dash);
        }
        return name.toUpperCase(Locale.ROOT);
    }
}
