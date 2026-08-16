package de.tasticgames.proxy.telemetry;

/**
 * Telemetry event type constants (see docs/tasticproxy-architecture.md).
 */
public final class TelemetryTypes {

    public static final String PROXY_STARTED = "proxy.started";
    public static final String PROXY_STOPPED = "proxy.stopped";
    public static final String PROXY_HEARTBEAT = "proxy.heartbeat";
    public static final String PROXY_API_FAILURE = "proxy.api_failure";
    public static final String PROXY_API_RECOVERED = "proxy.api_recovered";
    public static final String PLAYER_CONNECTION_ATTEMPT = "player.connection_attempt";
    public static final String PLAYER_CONNECTION_ALLOWED = "player.connection_allowed";
    public static final String PLAYER_CONNECTION_DENIED = "player.connection_denied";
    public static final String PLAYER_SESSION_STARTED = "player.session_started";
    public static final String PLAYER_SESSION_ENDED = "player.session_ended";
    public static final String PLAYER_SERVER_SWITCH = "player.server_switch";
    public static final String PLAYER_DISCONNECT = "player.disconnect";
    public static final String ROUTING_REQUESTED = "routing.requested";
    public static final String ROUTING_DECISION = "routing.decision";
    public static final String ROUTING_NO_TARGET = "routing.no_target";
    public static final String TRANSFER_STARTED = "transfer.started";
    public static final String TRANSFER_SUCCESS = "transfer.success";
    public static final String TRANSFER_FAILED = "transfer.failed";
    public static final String FALLBACK_STARTED = "fallback.started";
    public static final String FALLBACK_SUCCESS = "fallback.success";
    public static final String FALLBACK_FAILED = "fallback.failed";
    public static final String SERVER_HEALTH_CHANGED = "server.health_changed";
    public static final String SERVER_STATE_CHANGED = "server.state_changed";
    public static final String SERVER_DRAIN_STARTED = "server.drain_started";
    public static final String SERVER_DRAIN_COMPLETED = "server.drain_completed";
    public static final String MAINTENANCE_ENABLED = "maintenance.enabled";
    public static final String MAINTENANCE_DISABLED = "maintenance.disabled";
    public static final String MAINTENANCE_JOIN_DENIED = "maintenance.join_denied";
    public static final String ALPHA_ACCESS_GRANTED = "alpha.access_granted";
    public static final String ALPHA_ACCESS_REVOKED = "alpha.access_revoked";
    public static final String ALPHA_JOIN_DENIED = "alpha.join_denied";
    public static final String FRIEND_REQUESTED = "friend.requested";
    public static final String FRIEND_ACCEPTED = "friend.accepted";
    public static final String FRIEND_DENIED = "friend.denied";
    public static final String FRIEND_REMOVED = "friend.removed";
    public static final String PARTY_CREATED = "party.created";
    public static final String PARTY_INVITED = "party.invited";
    public static final String PARTY_JOINED = "party.joined";
    public static final String PARTY_LEFT = "party.left";
    public static final String PARTY_KICKED = "party.kicked";
    public static final String PARTY_PROMOTED = "party.promoted";
    public static final String PARTY_DISBANDED = "party.disbanded";
    public static final String PARTY_TRANSFER_STARTED = "party.transfer_started";
    public static final String PARTY_TRANSFER_COMPLETED = "party.transfer_completed";
    public static final String PARTY_TRANSFER_FAILED = "party.transfer_failed";
    public static final String CLAN_CREATED = "clan.created";
    public static final String CLAN_INVITED = "clan.invited";
    public static final String CLAN_JOINED = "clan.joined";
    public static final String CLAN_LEFT = "clan.left";
    public static final String CLAN_ROLE_CHANGED = "clan.role_changed";
    public static final String CLAN_DISBANDED = "clan.disbanded";
    public static final String ADMIN_COMMAND = "admin.command";

    private TelemetryTypes() {
    }
}
