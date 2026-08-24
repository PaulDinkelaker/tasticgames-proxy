package de.tasticgames.proxy.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kürzel und Farbe entstehen aus dem Clannamen – dieselbe Eingabe muss überall dasselbe ergeben,
 * sonst sieht derselbe Clan auf zwei Proxies verschieden aus.
 */
class ClanTagTest {

    private static final String TEMPLATE = "<gradient:<color1>:<color2>>[<tag>]</gradient> ";

    @Test
    void dasKuerzelSindDieErstenZeichenInGrossbuchstaben() {
        assertEquals("BAKER", ClanTag.of("Bakers United").tag());
        assertEquals("KEKS", ClanTag.of("Keks").tag());
        assertEquals("ABCDE", ClanTag.of("abcdefgh").tag(), "höchstens fünf Zeichen");
    }

    @Test
    void sonderzeichenUndLeerzeichenZaehlenNichtMit() {
        assertEquals("DIEKE", ClanTag.of("Die Kekse!").tag());
        assertEquals("TEAM1", ClanTag.of("[Team 1]").tag());
    }

    @Test
    void derselbeClanErgibtImmerDieselbenFarben() {
        ClanTag first = ClanTag.of("Bakers United");
        ClanTag second = ClanTag.of("bakers united");
        assertEquals(first.colorFrom(), second.colorFrom(), "Groß- und Kleinschreibung ändert die Farbe nicht");
        assertEquals(first.colorTo(), second.colorTo());
        assertNotEquals(first.colorFrom(), first.colorTo(), "Anfang und Ende des Verlaufs unterscheiden sich");
    }

    @Test
    void verschiedeneClansSehenVerschiedenAus() {
        assertNotEquals(ClanTag.of("Alpha").colorFrom(), ClanTag.of("Zulu").colorFrom());
    }

    @Test
    void dieVorlageWirdVollstaendigGefuellt() {
        ClanTag tag = ClanTag.of("Keks");
        String rendered = tag.render(TEMPLATE);
        assertTrue(rendered.contains("[KEKS]"), rendered);
        assertTrue(rendered.contains(tag.colorFrom()) && rendered.contains(tag.colorTo()), rendered);
        assertTrue(rendered.endsWith(" "), "das Leerzeichen trennt Kürzel und Name: " + rendered);
    }

    @Test
    void ohneClanGibtEsKeinKuerzel() {
        assertTrue(ClanTag.of("").isEmpty());
        assertTrue(ClanTag.of(null).isEmpty());
        assertEquals("", ClanTag.of("").render(TEMPLATE));
    }
}
