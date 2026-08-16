package de.tasticgames.proxy.server;

/** Technical health as observed by health pings. Independent of admin state. */
public enum ServerHealthState {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}
