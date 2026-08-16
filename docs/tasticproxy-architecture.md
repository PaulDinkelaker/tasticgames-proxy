# TasticProxy 1.0 – Architecture

## Lifecycle

`TasticProxyPlugin` (Velocity entry point) → `ProxyBootstrap` (composition root). Services implement `ProxyService`
(`id/start/stop`) and are started in this order, stopped in reverse; a failed start stops what already runs:

1. `ProxyConfigurationService` (config + env overrides, fail-fast validation, safe reload)
2. `ProxyMetrics`, `ProxyScheduler` (tracked Velocity tasks + async pool)
3. `ProxyApiClient` (wraps `TasticApiClient`; metrics, latency, availability)
4. `PlayerLanguageService`, `ProxyMessages` (MiniMessage bundles en/de, English fallback)
5. `TelemetryService` (bounded queue → `POST /telemetry/events`)
6. `ProxyRegistryService` (register/heartbeat/offline of this proxy instance)
7. `ServerRegistryService` (Velocity servers + config metadata + central admin state) → `ServerHealthService`
8. `MaintenanceService`, `AlphaAccessService` (central + last-known-good caches)
9. `NetworkPlayerManager` (runtime sessions) → `NetworkPresenceService` (presence v2, heartbeats, cleanup)
10. `CapacityReservationService` → `RoutingService` → `TransferService` → `FallbackService` → `DrainService`
11. `ApiNetworkCommandBus` → `NetworkNotificationService` → `SocialPlayerLookup` → `FriendService`, `PartyService`, `PartyTransferService`, `ClanService`
12. Listeners, commands, `markOnline()`

## Source of truth

Every network-wide state is stored by the API (MariaDB): proxies, servers (admin state, health observations),
maintenance, alpha access, presence/sessions, commands, telemetry, friends, parties (+ transfers), clans.
The proxy keeps local caches: server registry (LKG), maintenance/alpha cache files, player runtime sessions.

## Connection flow

`LoginEvent` → maintenance gate → alpha gate (both in-memory, no I/O) → `PostLoginEvent` creates the local session
(session id, proxy id, region) and loads the account language → `PlayerChooseInitialServerEvent` routes to the best
`initial-server-type` (LOBBY) → `ServerConnectedEvent` updates session + central presence (`connect`/`switch`) →
`DisconnectEvent` ends the session (`disconnect` with session id, so stale events after a reconnect are ignored).

## Routing engine

`RoutingService.evaluate` = eligibility filter (type/server, exclusions, current server, admin state, health,
region policy, capacity incl. reservations + safety margin) + score
`load (0..load-weight) + region-weight (same region) + weight·factor/100 + health (HEALTHY +10, DEGRADED −20)`,
deterministic tie-break by server id. `RoutingDecision` keeps every candidate → `/tasticproxy route`.

## Transfers

`TransferService` is the only place calling `createConnectionRequest`. Guards: online, not already transferring,
cooldown, target accepting, already there. Tracked `TransferOperation` (PLANNED → STARTED → terminal) with timeout
(`orTimeout`) and a watchdog. Party transfers (`PartyTransferService`): plan → route with group size → reserve
capacity → create central transfer row → connect local members / delegate remote members via bus
(`party.transfer_member` / `..._result`) → aggregate → SUCCESS / PARTIAL_FAILURE / FAILED → release reservation.

## Fallback / drain / maintenance / alpha

* Fallback: `KickedFromServerEvent` → route to fallback type excluding the failed server; loop guard (3 in 20 s) → disconnect.
* Drain: DRAINING (central) → excluded from routing → optional migration → OFFLINE when empty network-wide.
* Maintenance/alpha: central state, refresh interval + bus broadcast (`maintenance.changed`, `alpha.changed`).

## Multi-proxy command bus

`NetworkCommandBus` interface; `ApiNetworkCommandBus` submits durable commands (`POST /network/commands`, fan-out
per proxy done by the API), polls `GET /network/commands/poll`, remembers processed ids (idempotent redelivery),
acks asynchronously. Local targets are handled without a round trip. Types: `CommandTypes`.
Backend → network: `POST /api/v1/network/transfers` creates a `player.transfer`/`party.transfer` command for the
player's proxy.

## Localization

`messages/proxy_en.properties` (fallback) and `proxy_de.properties`; MiniMessage; placeholders `<name>`; player
language from the TasticGames account (`PlayerLanguageService`), otherwise client locale, otherwise English.

## Telemetry

`TelemetryTypes` lists every event; common fields: eventId, timestamp, source (proxy id), region, environment,
player, session, server, correlation, outcome, duration, attributes. Never blocks; drops are counted.
