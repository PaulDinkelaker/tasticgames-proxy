# TasticProxy 1.0 – Deployment

## Order (new API endpoints are required by this proxy version)

1. Database: Flyway migrations `V5`–`V15` of `tasticgames-api` are additive (new tables + nullable columns) and
   backwards compatible with the currently deployed proxy/core.
2. Deploy `tasticgames-api` (Spring Boot 4.1, Java 25). Required env: `TASTICGAMES_DB_PASSWORD`,
   `TASTIC_PROXY_API_KEY`, `TASTIC_CORE_LOBBY_API_KEY`. Verify `GET /api/v1/health` and
   `GET /api/v1/network/maintenance` (with service headers) → 200.
3. Deploy the proxy JAR (`build/libs/tasticgames-proxy-1.0.0.jar`) – see below.

## Proxy deployment steps (MintServers / Velocity)

1. Enable maintenance if needed (`/maintenance on Deploy`).
2. Wait for `/tasticproxy transfers` to show no active transfers.
3. Stop the Velocity server.
4. Backup: `backups/tasticproxy/<timestamp>/` ← current `plugins/tasticgames-proxy-*.jar` and `plugins/tasticproxy/`.
5. Remove the old proxy JAR from `plugins/` (exactly one TasticProxy JAR must remain).
6. Upload `tasticgames-proxy-1.0.0.jar` to `plugins/`.
7. Configuration: `plugins/tasticproxy/config.properties` – set `proxy.instance-id`, `proxy.region`,
   `proxy.environment`, `api.base-url`, `servers.<id>.*` for every Velocity server; provide the API key via
   `TASTIC_API_KEY` (preferred) or `api.authentication.api-key`. Old keys `network.*` from 0.1.0 are ignored
   (`alpha-access.txt` is no longer read – grant access via `/alphaaccess add`).
8. Start the server. Expected log lines: `Configuration loaded`, `TasticGames API client initialized`,
   `API connection established`, `Proxy … registered centrally`, `Server registry initialized`, `Loaded proxy
   messages`, `Network command bus started`, `Registered 8 proxy listeners`, `Registered 9 proxy commands`,
   `TasticProxy 1.0.0 bootstrap finished`. No `ClassNotFound`, no exceptions.
9. Smoke tests: status ping, join → lobby, `/tasticproxy status`, `/serverstatus`, `/tasticproxy route LOBBY`,
   `/maintenance status`, `/alphaaccess status`, `/friend list`, `/party info`, `/clan info`.
10. `/maintenance off`.

## Rollback

Stop the proxy → restore the backed-up JAR and `plugins/tasticproxy/` → start → verify logs. Migrations are additive,
the previous proxy version keeps working against the new API.

## Blockers on the build machine

No SFTP/panel credentials or SSH keys for MintServers were found on this machine – the deployment above must be
executed by an operator with access (or the credentials must be provided). All artifacts are built and tested locally.

## 1.0.1 notes
* `/lobby`, `/hub`, `/l` are forwarded to the backend while the player is on a server of type LOBBY (TasticLobby's own
  `/lobby` runs: spawn / leave the cookie open world); everywhere else the proxy transfers to a lobby.
* Startup verifies an authenticated endpoint (`GET /api/v1/network/servers`): `401/403` -> ERROR "rejected the credentials"
  (fix `api.authentication.api-key` / `TASTIC_API_KEY` vs. `tasticgames.security.service-auth.services.tastic-proxy`),
  `404` -> ERROR "does not know the 1.0 endpoints" (deploy tasticgames-api 1.0). Missing key -> ERROR at startup and a
  throttled ERROR whenever `/friend`, `/party`, `/clan` are refused in degraded mode.
* Proxy admin commands need `tasticproxy.*` permissions - install LuckPerms-Velocity (Velocity has no op concept).
