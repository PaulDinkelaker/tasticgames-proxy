package de.tasticgames.proxy.server;

import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.support.TestConfigurations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerHealthStateMachineTest {

    private final ProxyConfiguration.Health config = TestConfigurations.health();

    @Test
    void singleFailureDoesNotFlapToUnreachable() {
        assertEquals(ServerHealthState.DEGRADED, ServerHealthService.nextState(ServerHealthState.HEALTHY, 1, 0, 20, config));
        assertEquals(ServerHealthState.DEGRADED, ServerHealthService.nextState(ServerHealthState.HEALTHY, 2, 0, 20, config));
        assertEquals(ServerHealthState.UNREACHABLE, ServerHealthService.nextState(ServerHealthState.HEALTHY, 3, 0, 20, config));
    }

    @Test
    void unknownStaysUnknownOnEarlyFailures() {
        assertEquals(ServerHealthState.UNKNOWN, ServerHealthService.nextState(ServerHealthState.UNKNOWN, 1, 0, -1, config));
        assertEquals(ServerHealthState.UNREACHABLE, ServerHealthService.nextState(ServerHealthState.UNKNOWN, 3, 0, -1, config));
    }

    @Test
    void recoveryNeedsThreshold() {
        assertEquals(ServerHealthState.UNREACHABLE, ServerHealthService.nextState(ServerHealthState.UNREACHABLE, 0, 1, 20, config));
        assertEquals(ServerHealthState.HEALTHY, ServerHealthService.nextState(ServerHealthState.UNREACHABLE, 0, 2, 20, config));
        assertEquals(ServerHealthState.DEGRADED, ServerHealthService.nextState(ServerHealthState.UNREACHABLE, 0, 2, 900, config));
    }

    @Test
    void firstSuccessFromUnknownIsHealthy() {
        assertEquals(ServerHealthState.HEALTHY, ServerHealthService.nextState(ServerHealthState.UNKNOWN, 0, 1, 30, config));
        assertEquals(ServerHealthState.DEGRADED, ServerHealthService.nextState(ServerHealthState.HEALTHY, 0, 5, 1000, config));
    }

    @Test
    void networkServerTracksCounters() {
        NetworkServer server = new NetworkServer(new de.tasticgames.proxy.support.FakeRegisteredServer("lobby-1"), ServerType.LOBBY, "EU_FRANKFURT", 100, 100, java.util.List.of());
        java.time.Instant now = java.time.Instant.now();
        server.recordPingFailure("timeout", now);
        server.recordPingFailure("timeout", now);
        assertEquals(2, server.consecutiveFailures());
        server.recordPingSuccess(12, 5, now);
        assertEquals(0, server.consecutiveFailures());
        assertEquals(1, server.consecutiveSuccesses());
        assertEquals(12, server.latencyMillis());
        assertEquals(5, server.knownPlayerCount());
        assertEquals(ServerHealthState.UNKNOWN, server.applyHealth(ServerHealthState.HEALTHY, now));
        assertEquals(null, server.applyHealth(ServerHealthState.HEALTHY, now));
        assertEquals(true, server.acceptingPlayers());
        server.applyAdminState(ServerAdminState.DRAINING, "admin", "deploy", now, 1);
        assertEquals(false, server.acceptingPlayers());
        assertEquals(true, server.draining());
    }
}
