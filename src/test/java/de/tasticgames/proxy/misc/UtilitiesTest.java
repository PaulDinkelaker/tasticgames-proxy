package de.tasticgames.proxy.misc;

import de.tasticgames.proxy.bus.PayloadCodec;
import de.tasticgames.proxy.maintenance.MaintenanceState;
import de.tasticgames.proxy.social.RateLimiter;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.util.Ids;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilitiesTest {

    @Test
    void payloadCodecRoundTrip() {
        Map<String, String> payload = Map.of("player", "abc", "key", "friend.online", "p.player", "Alice");
        String json = PayloadCodec.encode(payload);
        assertEquals(payload, PayloadCodec.decode(json));
        assertEquals(Map.of(), PayloadCodec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> PayloadCodec.decode("not json"));
    }

    @Test
    void idsValidation() {
        assertEquals("lobby-1", Ids.serverId(" Lobby-1 "));
        assertThrows(IllegalArgumentException.class, () -> Ids.serverId("bad name"));
        assertTrue(Ids.isUsername("Steve_01"));
        assertFalse(Ids.isUsername("no"));
        assertTrue(Ids.parseUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5").isPresent());
        assertTrue(Ids.parseUuid("069a79f444e94726a5befca90e38aaf5").isPresent());
        assertTrue(Ids.parseUuid("Steve").isEmpty());
        assertTrue(Ids.isClanName("Tastic_1"));
        assertFalse(Ids.isClanName("Ta"));
    }

    @Test
    void rateLimiterSlidingWindowAndCooldown() {
        RateLimiter limiter = new RateLimiter();
        UUID player = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player, "x", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire(player, "x", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire(player, "x", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), "x", 2, Duration.ofMinutes(1)), "per player");
        assertTrue(limiter.tryCooldown(player, "y", Duration.ofSeconds(30)));
        assertFalse(limiter.tryCooldown(player, "y", Duration.ofSeconds(30)));
        assertTrue(limiter.tryCooldown(player, "y", Duration.ZERO));
    }

    @Test
    void telemetryBackoffIsBoundedAndGrows() {
        Duration max = Duration.ofSeconds(60);
        Duration first = TelemetryService.backoff(1, max);
        Duration later = TelemetryService.backoff(10, max);
        assertTrue(first.compareTo(Duration.ofSeconds(1)) >= 0);
        assertTrue(first.compareTo(Duration.ofSeconds(4)) <= 0);
        assertEquals(max, later);
        assertEquals(max, TelemetryService.backoff(100, max), "must never overflow");
    }

    @Test
    void maintenanceStateComparison() {
        MaintenanceState a = new MaintenanceState(true, "Deploy", null, false, java.time.Instant.now(), "admin", 3);
        MaintenanceState b = new MaintenanceState(true, "Deploy", null, false, java.time.Instant.EPOCH, "other", 4);
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(new MaintenanceState(false, "Deploy", null, false, null, null, 0)));
        assertEquals("unknown", new MaintenanceState(false, null, null, false, null, null, 0).changedBy());
    }
}
