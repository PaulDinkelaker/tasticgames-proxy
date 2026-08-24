package de.tasticgames.proxy.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Chatzeile entsteht aus einer Vorlage. Was ein Spieler tippt, wird niemals zu Markup – und
 * Farbcodes verschwinden, statt zu färben oder als Zeichen stehen zu bleiben.
 */
class ChatFormatTest {

    private static final String TEMPLATE = "<clan><name><dark_gray> >> <gray><message>";

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void ohneClanStehtNurDerName() {
        Component line = ChatFormat.render(TEMPLATE, "", "<gray>Ctastic_official", "Ctastic_official", "SURVIVAL",
                "hello world");
        assertEquals("Ctastic_official >> hello world", plain(line));
    }

    @Test
    void mitClanStehtDasKuerzelDavor() {
        String clan = ClanTag.of("Bakers United").render("<gradient:<color1>:<color2>>[<tag>]</gradient> ");
        Component line = ChatFormat.render(TEMPLATE, clan, "<red>Admin1", "Admin1", "LOBBY", "hi");
        assertEquals("[BAKER] Admin1 >> hi", plain(line));
    }

    @Test
    void eineNachrichtKannKeinMarkupEinschleusen() {
        Component line = ChatFormat.render(TEMPLATE, "", "<gray>Griefer", "Griefer", "LOBBY",
                "<red><click:run_command:'/op Griefer'>click me</click>");
        assertEquals("Griefer >> <red><click:run_command:'/op Griefer'>click me</click>", plain(line),
                "die Zeichen bleiben genau das, was der Spieler getippt hat");
    }

    @Test
    void farbcodesWerdenEntferntStattAngezeigtOderGefaerbt() {
        assertEquals("Hallo!", ChatFormat.sanitize("&aHallo!"));
        assertEquals("red bold", ChatFormat.sanitize("&cred &lbold"));
        assertEquals("hello", ChatFormat.sanitize("§chello"));
        assertEquals("bunt", ChatFormat.sanitize("&#ff00ffbunt"), "auch Hex-Codes verschwinden");

        Component line = ChatFormat.render(TEMPLATE, "", "<gray>Player", "Player", "LOBBY", "&aHallo!");
        assertEquals("Player >> Hallo!", plain(line));
    }

    @Test
    void einEinzelnesUndZeichenBleibtStehen() {
        assertEquals("Tom & Jerry", ChatFormat.sanitize("Tom & Jerry"), "das & ist hier ein normales Zeichen");
        assertEquals("100% & mehr", ChatFormat.sanitize("100% & mehr"));
    }

    @Test
    void steuerzeichenUndRandLeerraumVerschwinden() {
        assertEquals("ab", ChatFormat.sanitize("a\nb"), "Zeilenumbrüche fallen weg, statt die Zeile zu spalten");
        assertEquals("hi", ChatFormat.sanitize("  hi  "));
        assertTrue(ChatFormat.isBlank("&a&b"), "eine Nachricht aus reinen Farbcodes ist leer");
        assertFalse(ChatFormat.isBlank("&aok"));
    }

    @Test
    void serverKuerzel() {
        assertEquals("SURVIVAL", ChatFormat.serverTag("survival-1"));
        assertEquals("LOBBY", ChatFormat.serverTag("lobby"));
        assertEquals("", ChatFormat.serverTag(""));
    }
}
