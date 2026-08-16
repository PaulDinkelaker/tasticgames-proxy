package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.server.NetworkServer;
import de.tasticgames.proxy.server.ServerAdminState;
import de.tasticgames.proxy.server.ServerHealthState;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.support.FakeRegisteredServer;
import de.tasticgames.proxy.support.TestConfigurations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingServiceTest {

    private final ProxyConfiguration.Routing routing = TestConfigurations.routing();
    private final CapacityReservationService reservations = new CapacityReservationService(java.time.Duration.ofSeconds(20));
    private final Evaluator service = new Evaluator(reservations);

    /** Thin adapter around the static evaluation for readable tests. */
    private record Evaluator(CapacityReservationService reservations) {
        RoutingDecision evaluate(RoutingRequest request, ProxyConfiguration.Routing config, List<NetworkServer> servers) {
            return RoutingService.evaluate(request, config, servers, reservations);
        }
    }

    private static NetworkServer server(String id, ServerType type, String region, int capacity, int weight, ServerAdminState state,
                                        ServerHealthState health, int players) {
        NetworkServer server = new NetworkServer(new FakeRegisteredServer(id), type, region, capacity, weight, List.of());
        server.applyAdminState(state, "test", "", Instant.now(), 1);
        server.applyHealth(health, Instant.now());
        server.applyRemotePlayerCount(players, Instant.now());
        return server;
    }

    @Test
    void excludesDrainingMaintenanceOfflineAndUnreachable() {
        List<NetworkServer> servers = List.of(
                server("lobby-1", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.DRAINING, ServerHealthState.HEALTHY, 0),
                server("lobby-2", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.MAINTENANCE, ServerHealthState.HEALTHY, 0),
                server("lobby-3", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.OFFLINE, ServerHealthState.HEALTHY, 0),
                server("lobby-4", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.UNREACHABLE, 0),
                server("lobby-5", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 10));
        RoutingDecision decision = service.evaluate(RoutingRequest.forType(UUID.randomUUID(), ServerType.LOBBY, null, TransferReason.COMMAND), routing, servers);
        assertTrue(decision.found());
        assertEquals("lobby-5", decision.target().serverId());
        assertEquals(4, decision.excluded().size());
        assertTrue(decision.excluded().stream().anyMatch(c -> c.exclusionReason().contains("DRAINING")));
        assertTrue(decision.excluded().stream().anyMatch(c -> c.exclusionReason().contains("UNREACHABLE")));
    }

    @Test
    void prefersSameRegionAndLowerLoadDeterministically() {
        List<NetworkServer> servers = List.of(
                server("survival-in", ServerType.SURVIVAL, "IN_MUMBAI", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0),
                server("survival-eu-2", ServerType.SURVIVAL, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 60),
                server("survival-eu-1", ServerType.SURVIVAL, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 10),
                server("lobby-1", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0));
        RoutingDecision decision = service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.GATEWAY), routing, servers);
        assertEquals("survival-eu-1", decision.target().serverId());
        List<RoutingCandidate> eligible = decision.eligible();
        assertEquals("survival-eu-1", eligible.get(0).server().serverId());
        assertEquals("survival-eu-2", eligible.get(1).server().serverId());
        assertEquals("survival-in", eligible.get(2).server().serverId());
        // type filter
        assertTrue(decision.excluded().stream().anyMatch(c -> c.server().serverId().equals("lobby-1")));
    }

    @Test
    void tieBreakIsAlphabeticalWhenScoresEqual() {
        List<NetworkServer> servers = List.of(
                server("lobby-b", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0),
                server("lobby-a", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0));
        RoutingDecision decision = service.evaluate(RoutingRequest.forType(null, ServerType.LOBBY, null, TransferReason.INITIAL_JOIN), routing, servers);
        assertEquals("lobby-a", decision.target().serverId());
    }

    @Test
    void capacityIncludesGroupSizeMarginAndReservations() {
        NetworkServer nearlyFull = server("survival-1", ServerType.SURVIVAL, "EU_FRANKFURT", 10, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 6);
        List<NetworkServer> servers = List.of(nearlyFull);
        // free = 4, margin 2 -> 2 usable
        assertTrue(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.PARTY).withGroupSize(2), routing, servers).found());
        assertFalse(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.PARTY).withGroupSize(3), routing, servers).found());
        UUID reservation = reservations.reserve("survival-1", 2, nearlyFull.freeSlots());
        assertTrue(reservation != null);
        assertFalse(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.PARTY), routing, servers).found(),
                "reservation must consume the remaining slots");
        reservations.release(reservation);
        assertTrue(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.PARTY), routing, servers).found());
    }

    @Test
    void crossRegionCanBeDisabled() {
        List<NetworkServer> servers = List.of(
                server("survival-in", ServerType.SURVIVAL, "IN_MUMBAI", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0));
        assertTrue(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.GATEWAY), routing, servers).found());
        assertFalse(service.evaluate(RoutingRequest.forType(null, ServerType.SURVIVAL, null, TransferReason.GATEWAY), TestConfigurations.routingNoCrossRegion(), servers).found());
    }

    @Test
    void currentServerAndExclusionsAreRespected() {
        List<NetworkServer> servers = List.of(
                server("lobby-1", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 0),
                server("lobby-2", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 50));
        RoutingDecision decision = service.evaluate(RoutingRequest.forType(null, ServerType.LOBBY, "lobby-1", TransferReason.COMMAND), routing, servers);
        assertEquals("lobby-2", decision.target().serverId());
        RoutingRequest fallback = new RoutingRequest(null, ServerType.LOBBY, null, null, 1, Set.of("lobby-1", "lobby-2"), null, true, TransferReason.FALLBACK);
        assertFalse(service.evaluate(fallback, routing, servers).found());
        // concrete server request still validates state
        RoutingDecision concrete = service.evaluate(RoutingRequest.forServer(null, "lobby-2", null, TransferReason.ADMIN), routing, servers);
        assertEquals("lobby-2", concrete.target().serverId());
    }

    @Test
    void degradedIsEligibleButScoredLower() {
        List<NetworkServer> servers = List.of(
                server("lobby-1", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.DEGRADED, 0),
                server("lobby-2", ServerType.LOBBY, "EU_FRANKFURT", 100, 100, ServerAdminState.ONLINE, ServerHealthState.HEALTHY, 20));
        RoutingDecision decision = service.evaluate(RoutingRequest.forType(null, ServerType.LOBBY, null, TransferReason.COMMAND), routing, servers);
        assertEquals("lobby-2", decision.target().serverId());
        assertEquals(2, decision.eligible().size());
    }
}
