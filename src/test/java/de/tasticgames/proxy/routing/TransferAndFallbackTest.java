package de.tasticgames.proxy.routing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferAndFallbackTest {

    @Test
    void transferStatusSemantics() {
        assertTrue(TransferStatus.SUCCESS.successful());
        assertTrue(TransferStatus.ALREADY_CONNECTED.successful());
        assertFalse(TransferStatus.TIMEOUT.successful());
        assertFalse(TransferStatus.PLANNED.terminal());
        assertFalse(TransferStatus.STARTED.terminal());
        assertTrue(TransferStatus.CANCELLED.terminal());
    }

    @Test
    void transferOperationIsIdempotentOnCompletion() {
        TransferOperation op = new TransferOperation(UUID.randomUUID(), UUID.randomUUID(), "Alice", "lobby-1", "survival-1", TransferReason.GATEWAY, null);
        assertEquals(TransferStatus.PLANNED, op.status());
        op.started();
        assertEquals(TransferStatus.STARTED, op.status());
        assertTrue(op.finish(TransferStatus.SUCCESS, ""));
        assertFalse(op.finish(TransferStatus.TIMEOUT, "late"), "second completion must be ignored");
        assertEquals(TransferStatus.SUCCESS, op.status());
        assertEquals("survival-1", op.result().destination());
    }

    @Test
    void reservationsExpireAndRespectCapacity() {
        CapacityReservationService reservations = new CapacityReservationService(java.time.Duration.ofSeconds(20));
        UUID first = reservations.reserve("s", 3, 5);
        assertTrue(first != null);
        assertNull(reservations.reserve("s", 3, 5), "only 2 slots left");
        UUID second = reservations.reserve("s", 2, 5);
        assertTrue(second != null);
        assertEquals(5, reservations.reserved("s"));
        reservations.release(first);
        assertEquals(2, reservations.reserved("s"));
        assertTrue(reservations.reserve("t", 1, 1) != null);
    }

    @Test
    void fallbackLoopGuardTriggersOnThirdFallbackInWindow() {
        FallbackLoopGuard guard = new FallbackLoopGuard(java.time.Duration.ofSeconds(20), 2);
        UUID player = UUID.randomUUID();
        assertFalse(guard.recordAndDetectLoop(player));
        assertFalse(guard.recordAndDetectLoop(player));
        assertTrue(guard.recordAndDetectLoop(player), "third fallback within the window is a loop");
        guard.forget(player);
        assertFalse(guard.recordAndDetectLoop(player));
    }
}
