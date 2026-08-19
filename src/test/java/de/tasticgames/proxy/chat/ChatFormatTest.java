package de.tasticgames.proxy.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat line is built from a template; what a player typed never becomes markup. */
class ChatFormatTest {

    private static final String TEMPLATE = "<prefix><white><player><dark_gray> » <gray><message>";

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void nameAndMessageEndUpInTheLine() {
        Component line = ChatFormat.render(TEMPLATE, "<red>[Admin] ", "", "Ctastic_official", "SURVIVAL", "hello world", false);
        assertEquals("[Admin] Ctastic_official » hello world", plain(line));
    }

    @Test
    void aMessageCannotInjectMarkup() {
        Component line = ChatFormat.render(TEMPLATE, "", "", "Griefer", "LOBBY",
                "<red><click:run_command:'/op Griefer'>click me</click>", false);
        assertEquals("Griefer » <red><click:run_command:'/op Griefer'>click me</click>", plain(line),
                "the tags stay the characters the player typed");
    }

    @Test
    void colourCodesOnlyWorkWithThePermission() {
        String message = "&cred &lbold";
        assertEquals("Player » &cred &lbold", plain(ChatFormat.render(TEMPLATE, "", "", "Player", "LOBBY", message, false)));
        assertEquals("Player » red bold", plain(ChatFormat.render(TEMPLATE, "", "", "Player", "LOBBY", message, true)),
                "with the permission the codes become colours instead of text");
    }

    @Test
    void sectionSignsAndControlCharactersNeverSurvive() {
        assertEquals("hello", ChatFormat.sanitize("§chello"));
        assertEquals("a b", ChatFormat.sanitize("a\nb".replace("\n", " ")));
        assertEquals("clean", ChatFormat.sanitize("  clean\r\n "));
        assertTrue(ChatFormat.isBlank("   "));
        assertFalse(ChatFormat.isBlank(" x "));
    }

    @Test
    void legacyCodesBecomeMiniMessageTags() {
        assertEquals("<red>hi", ChatFormat.legacyToMiniMessage("&chi"));
        assertEquals("<bold><green>hi", ChatFormat.legacyToMiniMessage("&l&ahi"));
        assertEquals("a & b", ChatFormat.legacyToMiniMessage("a & b"), "a lone ampersand stays text");
        assertEquals("100&%", ChatFormat.legacyToMiniMessage("100&%"), "an unknown code stays text");
    }

    @Test
    void theServerTagIsTheShortUpperCaseName() {
        assertEquals("SURVIVAL", ChatFormat.serverTag("survival-1"));
        assertEquals("LOBBY", ChatFormat.serverTag("lobby"));
        assertEquals("", ChatFormat.serverTag(""));
        assertEquals("", ChatFormat.serverTag(null));
    }

    @Test
    void prefixAndTitleAreRenderedAsMarkupBecauseTheyComeFromTheNetwork() {
        Component line = ChatFormat.render("<prefix><title><player>: <message>", "<red>[Admin] ", "<gold>[Baker] ",
                "Someone", "LOBBY", "hi", false);
        assertEquals("[Admin] [Baker] Someone: hi", plain(line));
    }
}
