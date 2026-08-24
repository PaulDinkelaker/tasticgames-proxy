package de.tasticgames.proxy.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;
import java.util.Objects;

/**
 * Baut eine Chatzeile aus einer MiniMessage-Vorlage. Reine Zeichenkettenarbeit, damit die
 * Escaping-Regeln ohne Proxy prüfbar sind.
 *
 * <p>Die Zeile besteht aus drei Teilen: dem Clan-Kürzel (nur wenn der Spieler in einem Clan ist),
 * dem Namen in der Farbe seines Ranges und der Nachricht. Was ein Spieler tippt, wird niemals als
 * MiniMessage gelesen – es geht als reiner Text durch einen Platzhalter. Farbcodes gibt es im Chat
 * nicht: {@code &a} und {@code §a} werden entfernt, nicht angezeigt und nicht umgesetzt. Damit kann
 * niemand Farben, Hover-Texte oder Klickbefehle in den Chat schreiben, und die Zeile bleibt
 * einheitlich lesbar.</p>
 */
public final class ChatFormat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Die klassischen Codes, die Spieler tippen – sie werden samt Zeichen davor entfernt. */
    private static final String LEGACY_CODES = "0123456789abcdefklmnorxABCDEFKLMNORX";

    private ChatFormat() {
    }

    /**
     * Rendert eine Chatzeile.
     *
     * @param template MiniMessage-Vorlage mit {@code <clan>}, {@code <name>}, {@code <player>},
     *                 {@code <server>} und {@code <message>}
     * @param clan     fertiges Clan-Kürzel als MiniMessage, oder leer
     * @param name     Name samt Rangfarbe als MiniMessage (kommt vom Server, nicht vom Spieler)
     * @param player   reiner Name des Spielers
     * @param server   Anzeigename des Servers, von dem die Nachricht kam
     * @param message  was der Spieler getippt hat
     */
    public static Component render(String template, String clan, String name, String player, String server,
                                   String message) {
        Objects.requireNonNull(template, "template");
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("clan", clan == null ? "" : clan),
                Placeholder.parsed("name", name == null ? "" : name),
                Placeholder.unparsed("player", player == null ? "" : player),
                Placeholder.unparsed("server", server == null ? "" : server),
                Placeholder.unparsed("message", sanitize(message)));
        return MINI.deserialize(template, resolver);
    }

    /**
     * Entfernt, was nie bei anderen Spielern ankommen darf: Farbcodes ({@code &a}, {@code §a},
     * {@code &#rrggbb}), Steuerzeichen und Leerraum am Rand. Der getippte Text selbst bleibt
     * unverändert – nur die Codes verschwinden, sodass aus {@code &aHallo!} ein schlichtes
     * {@code Hallo!} wird.
     */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '§' || c == '&') {
                char next = i + 1 < message.length() ? message.charAt(i + 1) : 0;
                if (next == '#' && i + 7 < message.length() && isHex(message, i + 2, 6)) {
                    i += 7; // &#rrggbb
                    continue;
                }
                if (LEGACY_CODES.indexOf(next) >= 0) {
                    i++; // das Zeichen und sein Code gehören zusammen
                    continue;
                }
                if (c == '§') {
                    continue; // ein einzelnes § hat im Chat nichts zu suchen
                }
                // ein einzelnes & ist ein normales Zeichen ("Tom & Jerry")
            }
            if (c == '\n' || c == '\r' || (c < ' ' && c != '\t')) {
                continue;
            }
            clean.append(c);
        }
        return clean.toString().strip();
    }

    private static boolean isHex(String text, int from, int length) {
        for (int i = from; i < from + length; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Ob die Nachricht überhaupt etwas enthält (nach dem Entfernen der Codes). */
    public static boolean isBlank(String message) {
        return sanitize(message).isEmpty();
    }

    /** Kurzform einer Server-ID für die Chatzeile: {@code survival-1} wird zu {@code SURVIVAL}. */
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
