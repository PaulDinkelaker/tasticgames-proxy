package de.tasticgames.proxy.config;

/**
 * Every permission node used by TasticProxy (LuckPerms manages assignment).
 */
public final class ProxyPermissions {

    public static final String ADMIN = "tasticproxy.admin";
    public static final String STATUS = "tasticproxy.status";
    public static final String RELOAD = "tasticproxy.reload";
    public static final String MAINTENANCE_ADMIN = "tasticproxy.maintenance.admin";
    public static final String MAINTENANCE_BYPASS = "tasticproxy.maintenance.bypass";
    public static final String ALPHA_ADMIN = "tasticproxy.alpha.admin";
    public static final String ALPHA_BYPASS = "tasticproxy.alpha.bypass";
    public static final String SERVER_STATUS = "tasticproxy.server.status";
    public static final String SERVER_MANAGE = "tasticproxy.server.manage";
    public static final String PLAYER_INSPECT = "tasticproxy.player.inspect";
    public static final String ROUTING_BYPASS = "tasticproxy.routing.bypass";
    public static final String ROUTING_DIAGNOSE = "tasticproxy.routing.diagnose";
    public static final String SOCIAL_BYPASS_RATELIMIT = "tasticproxy.social.bypass-ratelimit";

    private ProxyPermissions() {
    }
}
