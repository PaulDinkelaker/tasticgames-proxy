# TasticProxy 1.0

TasticProxy is the network control plane of **TasticGames.de** – the single central Velocity plugin
(next to LuckPerms) that owns connection lifecycle, sessions, presence, maintenance, alpha access,
proxy/server registries, health, routing, transfers, party transfers, fallback, drain, friends,
parties, clans, network notifications, multi-proxy coordination, diagnostics and telemetry.

* Velocity 3.5.x · Java 25 · Gradle (Kotlin DSL) · shared `tasticgames-api-client` · TasticGames API (Spring Boot) + MariaDB
* Version: `1.0.0` (see `build-info.properties` inside the JAR / `/tasticproxy status`)

Documentation: [Architecture](docs/tasticproxy-architecture.md) · [Deployment](docs/tasticproxy-deployment.md) ·
[Plan](docs/tasticproxy-1.0-plan.md) · [Progress](docs/tasticproxy-1.0-progress.md)

## Build

```bash
# 1. shared client (composite build is used automatically, publishToMavenLocal is optional)
cd tasticgames-api-client && ./gradlew clean build publishToMavenLocal
# 2. proxy (JAVA_HOME must point to a JDK 25)
cd tasticgames-proxy && ./gradlew clean build
# -> build/libs/tasticgames-proxy-1.0.0.jar (shadow JAR, api-client + Jackson relocated to de.tasticgames.proxy.libs)
```

## Configuration (`plugins/tasticproxy/config.properties`)

Created with defaults on first start. Secrets should come from environment variables.

| Key | Env override | Default | Reloadable | Description |
|---|---|---|---|---|
| `proxy.instance-id` | `TASTIC_PROXY_ID` | generated `proxy-xxxxxxxx` (kept in `instance-id`) | no | unique proxy id (`[a-z0-9._-]`) |
| `proxy.name` | – | `TasticProxy` | no | display name |
| `proxy.region` | `TASTIC_PROXY_REGION` | `EU_FRANKFURT` | no | region of this proxy (`EU_FRANKFURT`, `IN_MUMBAI`, …) |
| `proxy.environment` | `TASTIC_PROXY_ENVIRONMENT` | `alpha` | no | environment tag |
| `proxy.host` | `TASTIC_PROXY_HOST` | – | no | optional host identifier |
| `proxy.heartbeat-interval-seconds` | – | 15 | no | central heartbeat |
| `api.enabled` | – | true | no | API integration |
| `api.base-url` | `TASTIC_API_BASE_URL` | `https://api.tasticgames.de/` | no | |
| `api.authentication.service-name` | `TASTIC_API_SERVICE` | `tastic-proxy` | no | `X-Tastic-Service` |
| `api.authentication.api-key` | `TASTIC_API_KEY` | `CHANGE_ME` | no | `X-Tastic-Api-Key` (never logged) |
| `api.timeouts.connect-seconds` / `request-seconds` | – | 5 / 10 | no | |
| `api.require-on-startup` | – | false | no | fail startup when the API is unreachable |
| `maintenance.fallback-enabled`, `kick-online-players`, `refresh-interval-seconds` | – | false / false / 30 | yes | local defaults + refresh |
| `alpha-access.fallback-enabled`, `deny-when-state-unknown`, `refresh-interval-seconds` | – | true / true / 60 | yes | fail-safe join gate |
| `health.ping-interval-seconds`, `ping-timeout-seconds`, `failure-threshold`, `recovery-threshold`, `stale-threshold-seconds`, `degraded-latency-millis` | – | 10 / 3 / 3 / 2 / 60 / 750 | yes | server health |
| `routing.preferred-region`, `cross-region-fallback`, `capacity-safety-margin`, `initial-server-type`, `fallback-server-type` | – | proxy region / true / 2 / LOBBY / LOBBY | yes | routing policy |
| `routing.transfer-timeout-seconds`, `transfer-cooldown-millis`, `reservation-timeout-seconds` | – | 15 / 1500 / 20 | yes | transfers |
| `routing.scoring.load-weight`, `region-weight`, `server-weight-factor` | – | 100 / 200 / 1 | yes | scoring |
| `presence.heartbeat-interval-seconds`, `stale-after-seconds`, `cleanup-interval-seconds` | – | 60 / 180 / 120 | no | presence |
| `social.friend-request-cooldown-seconds`, `max-friends`, `party-max-size`, `party-invite-timeout-seconds`, `party-idle-cleanup-seconds`, `clan-member-limit`, `clan-invite-timeout-seconds`, `invite-rate-window-seconds`, `invite-rate-limit`, `friend-join-notifications` | – | 10 / 200 / 8 / 60 / 300 / 50 / 300 / 60 / 10 / true | yes | social limits |
| `bus.poll-interval-millis`, `poll-batch-size`, `command-ttl-seconds` | – | 1000 / 50 / 60 | no | command bus |
| `telemetry.enabled`, `queue-capacity`, `batch-size`, `flush-interval-seconds`, `max-backoff-seconds` | – | true / 5000 / 200 / 5 / 60 | yes | telemetry |
| `servers.<id>.type`, `region`, `capacity`, `weight`, `tags` | – | `lobby` = LOBBY | yes | static metadata per Velocity server |

`/tasticproxy reload` re-reads the file and swaps only the reloadable sections.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/tasticproxy status` (`/tproxy`) | `tasticproxy.status` (or `.admin`) | version, uptime, proxy id/region, API state & latency, players/sessions, servers, health checks, routing, transfers, fallback, presence, bus, telemetry |
| `/tasticproxy health` | `tasticproxy.status` | detailed per-server health report |
| `/tasticproxy reload` | `tasticproxy.reload` | reload reloadable configuration |
| `/tasticproxy player <name|uuid>` | `tasticproxy.player.inspect` | local session, backend, pending transfer, central presence, party, clan |
| `/tasticproxy route <LOBBY|SURVIVAL|server> [player|groupSize]` | `tasticproxy.routing.diagnose` | routing diagnostics: candidates, exclusions, scores |
| `/tasticproxy proxies` / `transfers` | `tasticproxy.status` | known proxy instances / active + recent transfers |
| `/serverstatus [server]` (`/servers`) | `tasticproxy.server.status` | list / details (type, region, state, health, players, capacity, latency, last check) |
| `/serverstatus <server> state <ONLINE|MAINTENANCE|OFFLINE|DRAINING>` / `drain [migrate]` / `undrain` / `health` | `tasticproxy.server.manage` | central state changes, drain (+ migration), health ping |
| `/maintenance status|on [reason]|off|reason <text>|kick|refresh` | `tasticproxy.maintenance.admin` | central maintenance mode |
| `/alphaaccess status|on|off|add|remove|check <player|uuid>|list [page]|refresh` (`/alpha`) | `tasticproxy.alpha.admin` | central alpha allow-list |
| `/lobby` (`/hub`, `/l`) | – | route to the best lobby |
| `/friend add|accept|deny|remove|cancel <player>` · `list` · `requests` (`/friends`, `/f`) | – | friends |
| `/party invite|accept|deny|kick|promote <player>` · `leave` · `disband` · `info` · `chat <msg>` · `warp <LOBBY|SURVIVAL>` (`/p`), `/pc <msg>` | – | parties incl. atomic-style party transfer |
| `/clan create|info|invite|accept|deny|request|requests|acceptrequest|denyrequest|kick|promote|demote|leave|disband` (`/c`) | – | clans (alpha) |
| `/passadmin grant|revoke <player> [season]` · `addxp <player> <amount>` · `setlevel <player> <level>` · `season <activate|end> <season>` · `season import <file>` | `tasticproxy.pass.admin` | season pass administration (the player-facing `/pass` belongs to TasticLobby) |

## Permissions

`tasticproxy.admin` (all admin functions), `tasticproxy.status`, `tasticproxy.reload`, `tasticproxy.maintenance.admin`,
`tasticproxy.maintenance.bypass`, `tasticproxy.alpha.admin`, `tasticproxy.alpha.bypass`, `tasticproxy.server.status`,
`tasticproxy.server.manage`, `tasticproxy.player.inspect`, `tasticproxy.routing.bypass` (connect to non-accepting servers),
`tasticproxy.routing.diagnose`, `tasticproxy.social.bypass-ratelimit`, `tasticproxy.pass.admin`. LuckPerms assigns them.

## Season pass

The proxy never registers `/pass`, `/battlepass` or `/bp`: Velocity executes registered commands itself and never
forwards them, so those names stay with TasticLobby, which owns the player-facing pass. The proxy contributes

* `/passadmin` – premium entitlements, admin XP/level and season lifecycle through `/api/v1/pass/**`
  (every mutating call carries its own operation/order id and is safely retryable),
* `season import <file>` – reads a season JSON **inside the proxy data directory** (`plugins/tasticproxy/`;
  absolute paths, `..` segments and symlinks leaving the directory are refused) and POSTs it to
  `/admin/seasons/import`; the API's validation errors are shown to the operator instead of a generic failure,
* level-up notifications: game servers publish the command bus topic `pass.level_up` with the flat string payload
  `player` (UUID, optional when the command is addressed to a player), `fromLevel`, `toLevel`, `season` and
  `rewards`. The payload is parsed defensively and delivered through the network notification service
  (`pass.level_up`, `pass.level_up.rewards`); a broadcast is only answered by the proxy the player is on.

With the API offline `/passadmin` answers "unavailable" and no pass state is invented.

## Server states

* Administrative (central, operator): `ONLINE`, `DRAINING`, `MAINTENANCE`, `OFFLINE`
* Health (observed): `UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNREACHABLE` – thresholds prevent flapping.
* Routing only selects `ONLINE` servers that are not `UNREACHABLE`, with free capacity for the group (minus reservations and safety margin).

## Regions

`EU_FRANKFURT` and `IN_MUMBAI` are prepared; any `[A-Z0-9_]{2,32}` value works. Same-region servers are preferred
(`region-weight` dominates load), cross-region is a policy switch. The proxy instance defines the region – no IP geolocation.

## Failure strategy

| Domain | API down |
|---|---|
| Maintenance / alpha access | last-known-good cache files (`maintenance-cache.properties`, `alpha-access-cache.txt`); alpha denies unknown players when no state was ever loaded (`deny-when-state-unknown`) |
| Server state | local copy + local health, re-sync when the API returns |
| Presence / sessions | queued per player, heartbeat retries, stale sessions cleaned by any proxy |
| Telemetry | bounded queue, batches, exponential backoff, counted drops |
| Social | no data is invented; players get a localized "unavailable" message |
| Command bus | local commands still work; remote delivery resumes with polling |
