package de.tasticgames.proxy.chat;

import java.util.Locale;
import java.util.Objects;

/**
 * Das Clan-Kürzel, das in der Chatzeile vor dem Namen steht.
 *
 * <p>Die API kennt heute nur den Clannamen – kein Kürzel und keine Farbe. Beides wird deshalb hier
 * aus dem Namen abgeleitet: das Kürzel aus den ersten Zeichen, die Farben aus dem Hashwert des
 * Namens. Das hat zwei Eigenschaften, auf die es ankommt: derselbe Clan sieht auf jedem Proxy und
 * nach jedem Neustart gleich aus, und zwei Clans sehen mit hoher Wahrscheinlichkeit verschieden aus.
 * Sobald der Clan in der API ein eigenes Kürzel und eine eigene Farbe bekommt, ersetzt das hier
 * genau eine Methode.</p>
 *
 * @param tag       Kürzel in Großbuchstaben, höchstens {@link #MAX_LENGTH} Zeichen
 * @param colorFrom Startfarbe des Verlaufs ({@code #rrggbb})
 * @param colorTo   Endfarbe des Verlaufs
 */
public record ClanTag(String tag, String colorFrom, String colorTo) {

    public static final int MAX_LENGTH = 5;

    /** Kräftige, gut lesbare Farben; Grautöne fehlen bewusst, die gehören den Rängen. */
    private static final String[] PALETTE = {
            "#ff5555", "#ff8c1a", "#ffd21a", "#8cff3d", "#2fd96b",
            "#28d9c8", "#3fa9ff", "#6b6bff", "#b46bff", "#ff5fd2"
    };

    public ClanTag {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(colorFrom, "colorFrom");
        Objects.requireNonNull(colorTo, "colorTo");
    }

    /** Leitet Kürzel und Farbverlauf aus dem Clannamen ab. */
    public static ClanTag of(String clanName) {
        String source = clanName == null ? "" : clanName.trim();
        if (source.isEmpty()) {
            return new ClanTag("", PALETTE[0], PALETTE[0]);
        }
        String letters = source.replaceAll("[^A-Za-z0-9]", "");
        String base = letters.isEmpty() ? source : letters;
        String tag = base.substring(0, Math.min(MAX_LENGTH, base.length())).toUpperCase(Locale.ROOT);

        int hash = Math.abs(source.toLowerCase(Locale.ROOT).hashCode());
        String from = PALETTE[hash % PALETTE.length];
        // ein Abstand von drei Feldern hält Anfang und Ende sichtbar auseinander
        String to = PALETTE[(hash % PALETTE.length + 3) % PALETTE.length];
        return new ClanTag(tag, from, to);
    }

    public boolean isEmpty() {
        return tag.isEmpty();
    }

    /**
     * Baut das Kürzel in die Vorlage ein ({@code chat.clan-tag}), inklusive Farbverlauf.
     *
     * @param template MiniMessage mit den Platzhaltern {@code <tag>}, {@code <color1>}, {@code <color2>}
     */
    public String render(String template) {
        if (isEmpty() || template == null || template.isBlank()) {
            return "";
        }
        return template
                .replace("<color1>", colorFrom)
                .replace("<color2>", colorTo)
                .replace("<tag>", tag);
    }
}
