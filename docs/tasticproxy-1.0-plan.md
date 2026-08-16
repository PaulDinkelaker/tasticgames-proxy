# TasticProxy 1.0 – Implementation Plan

Status: living document, updated while implementing. Progress in `tasticproxy-1.0-progress.md`.

## Audit result (2026-08-16)

- Repositories: `tasticgames-proxy`, `tasticgames-lobby`, `tasticgames-api-client` had **no VCS**; `tasticgames-api` and
  `tasticgames-core` are git repos with large uncommitted user changes (left untouched, only additive changes).
- Baseline: api-client / core / proxy / api built green with Java 25 (Temurin 25.0.4). Lobby build was broken
  (referenced `tasticgames-core-0.1.0-SNAPSHOT.jar`, core is 1.0.0) → fixed with a version-agnostic `fileTree`.
- API test `contextLoads` needed a real DB → added `application-test.properties` (H2 in MariaDB mode + Flyway).
- No tests, no docs, no deployment scripts, no SFTP/SSH credentials found on this machine.
- Existing proxy code (maintenance, alpha, sessions, presence, registry, transfer, fallback, drain) is a sound
  skeleton but: file/RAM based state, no proxy identity, no health, no routing engine, no social, no telemetry.

## Architecture decisions

| Topic | Decision |
|---|---|
| Source of truth | TasticGames API + MariaDB for every network-wide state (proxies, servers, maintenance, alpha, presence, social). Proxy holds last-known-good caches only. |
| Multi-proxy bus | `NetworkCommandBus` interface; implementation is API/MySQL polling (`network_commands`). Broadcasts are fanned out server-side per online proxy. Redis/Kafka can replace the implementation later. |
| Health | `ServerHealthService` pings via `RegisteredServer#ping()` with failure/recovery thresholds; observations reported to API. |
| Routing | `RoutingService` = eligibility filter + deterministic scoring (region, load, weight, tie-break by id). Diagnosable via `/tasticproxy route`. |
| Transfers | `TransferService` owns every `createConnectionRequest`; tracked `TransferOperation` with timeout/cooldown; party transfers via `PartyTransferService` (plan → reserve → connect all → verify → release). |
| Localization | Own proxy message layer (`ProxyMessages`, MiniMessage, en/de, en fallback), player language from API account. |
| Telemetry | `TelemetryService`: bounded queue, batched `POST /telemetry/events`, backoff, drop counter. |
| Config | `config.properties` typed via `ProxyConfiguration`, env overrides (`TASTIC_API_KEY`, `TASTIC_API_BASE_URL`, `TASTIC_PROXY_ID`, `TASTIC_PROXY_REGION`, `TASTIC_PROXY_ENVIRONMENT`), safe reload for reloadable values only. |

## API contract (new endpoints, all `/api/v1`)

See `tasticgames-api-client` (`NetworkApi`, `SocialApi`, `LobbyApi`) – the client is the typed contract.

## Phases

A audit/baseline ✓ → B config/identity → C client → D proxy registry → E registry v2 → F health → G maintenance
→ H alpha → I sessions/presence → J routing → K transfers → L fallback/recovery → M drain → N command bus
→ O friends → P party → Q party transfers → R clan → S localization/notifications → T telemetry → U admin/diag
→ V tests → W docs → X deployment (blocked: no credentials on this machine).
