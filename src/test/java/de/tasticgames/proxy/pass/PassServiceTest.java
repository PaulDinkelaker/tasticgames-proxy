package de.tasticgames.proxy.pass;

import de.tasticgames.client.dto.pass.PassQuestScopeResponse;
import de.tasticgames.client.dto.pass.PassRewardTypeResponse;
import de.tasticgames.client.dto.pass.PassSeasonImportRequest;
import de.tasticgames.client.dto.pass.PassTrackResponse;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassServiceTest {

    private static final String SEASON_JSON = """
            {
              "key": "season-2",
              "displayName": "Season 2",
              "maxLevel": 100,
              "xpBase": 500,
              "xpGrowth": 25,
              "premiumPriceCents": 999,
              "currency": "EUR",
              "startsAt": "2026-09-01T00:00:00Z",
              "activate": true,
              "tiers": [
                {"level": 1, "track": "FREE", "rewardType": "COOKIES", "rewardAmount": 25000, "displayKey": "pass.reward.cookies"}
              ],
              "quests": [
                {"key": "daily_clicks", "scope": "DAILY", "game": "COOKIE", "metric": "cookie.clicks", "target": 500,
                 "xpReward": 150, "displayKey": "pass.quest.daily_clicks", "sortOrder": 1, "active": true}
              ],
              "dailyXpCaps": {"COOKIE_CLICKS": 400}
            }
            """;

    @Test
    void seasonKeyValidation() {
        assertTrue(PassService.validSeasonKey("season-1"));
        assertTrue(PassService.validSeasonKey(" season_1.2 "));
        assertFalse(PassService.validSeasonKey(null));
        assertFalse(PassService.validSeasonKey(""));
        assertFalse(PassService.validSeasonKey("season 1"));
        assertFalse(PassService.validSeasonKey("../etc"));
        assertFalse(PassService.validSeasonKey("s".repeat(65)));
    }

    @Test
    void seasonImportFilesMustStayInsideTheDataDirectory(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        Files.writeString(dir.resolve("season.json"), SEASON_JSON);
        Files.createDirectory(dir.resolve("seasons"));
        Files.writeString(dir.resolve("seasons").resolve("nested.json"), SEASON_JSON);
        Path outside = elsewhere.resolve("outside.json");
        Files.writeString(outside, SEASON_JSON);

        assertTrue(Files.isSameFile(dir.resolve("season.json"), PassService.resolveImportFile(dir, "season.json")));
        assertTrue(Files.isSameFile(dir.resolve("seasons").resolve("nested.json"),
                PassService.resolveImportFile(dir, "seasons/nested.json")));

        assertThrows(NoSuchFileException.class, () -> PassService.resolveImportFile(dir, "missing.json"));
        assertThrows(NoSuchFileException.class, () -> PassService.resolveImportFile(dir, "seasons"), "a directory is not a file");
        assertRefused(dir, outside.toString());
        assertRefused(dir, "../" + elsewhere.getFileName() + "/outside.json");
        assertRefused(dir, "..");
        assertRefused(dir, ".");
        assertRefused(dir, "  ");
        assertRefused(dir, null);
    }

    /** Refused before the file system is touched – never reported as "file not found". */
    private static void assertRefused(Path dir, String fileName) {
        IOException failure = assertThrows(IOException.class, () -> PassService.resolveImportFile(dir, fileName));
        assertFalse(failure instanceof NoSuchFileException, "must be refused as a path violation: " + fileName);
    }

    @Test
    void readsSeasonImportFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("season.json"), SEASON_JSON);
        PassSeasonImportRequest request = PassService.readSeasonImport(dir, "season.json");
        assertEquals("season-2", request.key());
        assertEquals(100, request.maxLevel().intValue());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), request.startsAt());
        assertNull(request.endsAt(), "unset values stay unset so the API keeps its own");
        assertEquals(Boolean.TRUE, request.activate());
        assertEquals(1, request.tiers().size());
        assertEquals(PassTrackResponse.FREE, request.tiers().getFirst().track());
        assertEquals(PassRewardTypeResponse.COOKIES, request.tiers().getFirst().rewardType());
        assertEquals(25000, request.tiers().getFirst().rewardAmount());
        assertEquals(PassQuestScopeResponse.DAILY, request.quests().getFirst().scope());
        assertEquals(Map.of("COOKIE_CLICKS", 400), request.dailyXpCaps());
    }

    @Test
    void rejectsSeasonFilesWithoutAKey(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("broken.json"), "{\"displayName\": \"Season 2\"}");
        Files.writeString(dir.resolve("garbage.json"), "not json");
        assertThrows(IOException.class, () -> PassService.readSeasonImport(dir, "broken.json"));
        assertThrows(IOException.class, () -> PassService.readSeasonImport(dir, "garbage.json"));
    }

    @Test
    void levelUpPayloadIsParsedDefensively() {
        UUID player = UUID.randomUUID();
        NetworkCommand addressed = command(player, Map.of("fromLevel", "7", "toLevel", "8",
                "season", "Season 1 - Crumbs & Crowns", "rewards", "500 Crumbs"));
        assertEquals(player, PassService.levelUpPlayer(addressed));
        Map<String, String> placeholders = PassService.levelUpPlaceholders(addressed);
        assertEquals("7", placeholders.get("from"));
        assertEquals("8", placeholders.get("to"));
        assertEquals("Season 1 - Crumbs & Crowns", placeholders.get("season"));
        assertEquals("500 Crumbs", placeholders.get("rewards"));

        NetworkCommand broadcast = command(null, Map.of("player", player.toString(), "toLevel", " 3 "));
        assertEquals(player, PassService.levelUpPlayer(broadcast));
        Map<String, String> defaults = PassService.levelUpPlaceholders(broadcast);
        assertEquals("0", defaults.get("from"));
        assertEquals("3", defaults.get("to"));
        assertEquals("-", defaults.get("season"));
        assertEquals("", defaults.get("rewards"));

        assertNull(PassService.levelUpPlayer(command(null, Map.of("player", "not-a-uuid", "toLevel", "2"))));
        assertNull(PassService.levelUpPlaceholders(command(player, Map.of("toLevel", "0"))), "no level, no message");
        assertNull(PassService.levelUpPlaceholders(command(player, Map.of("toLevel", "many"))), "garbage is not a level");
        assertNull(PassService.levelUpPlaceholders(command(player, Map.of())));

        Map<String, String> capped = PassService.levelUpPlaceholders(command(player,
                Map.of("toLevel", "2", "rewards", "x".repeat(500))));
        assertEquals(200, capped.get("rewards").length(), "remote payloads must not flood the chat");
    }

    private static NetworkCommand command(UUID target, Map<String, String> payload) {
        return new NetworkCommand(UUID.randomUUID(), CommandTypes.PASS_LEVEL_UP, "proxy-other", target, payload,
                Instant.now(), Instant.now().plusSeconds(60));
    }
}
