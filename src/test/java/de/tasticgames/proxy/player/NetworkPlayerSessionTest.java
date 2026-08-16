package de.tasticgames.proxy.player;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerSessionTest {

    @Test
    void lifecycleAndTransferGuards() {
        NetworkPlayerSession session = new NetworkPlayerSession(UUID.randomUUID(), "Alice", "proxy-1", "EU_FRANKFURT", Instant.now());
        assertEquals(NetworkPlayerState.CONNECTING, session.state());
        assertTrue(session.serverConnected("Lobby-1", Instant.now()), "first server");
        assertEquals("lobby-1", session.currentServer().orElseThrow());
        assertFalse(session.serverConnected("survival-1", Instant.now()));
        assertEquals("lobby-1", session.previousServer().orElseThrow());
        assertEquals(NetworkPlayerState.CONNECTED, session.state());

        UUID transfer = UUID.randomUUID();
        assertTrue(session.beginTransfer(transfer, Instant.now()));
        assertEquals(NetworkPlayerState.TRANSFERRING, session.state());
        assertFalse(session.beginTransfer(UUID.randomUUID(), Instant.now()), "no concurrent transfers");
        session.endTransfer(UUID.randomUUID());
        assertTrue(session.pendingTransferId().isPresent(), "unknown id must not clear the pending transfer");
        session.endTransfer(transfer);
        assertTrue(session.pendingTransferId().isEmpty());
        assertEquals(NetworkPlayerState.CONNECTED, session.state());

        session.beginDisconnect();
        assertThrows(IllegalStateException.class, () -> session.serverConnected("lobby-1", Instant.now()));
    }

    @Test
    void invalidUsernameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkPlayerSession(UUID.randomUUID(), "bad name!", "p", "R", Instant.now()));
    }
}
