package de.tasticgames.proxy.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable identity of this proxy instance.
 */
public record ProxyIdentity(
        String proxyId,
        String proxyName,
        String region,
        String environment,
        String version,
        String host,
        Instant startedAt
) {
    public ProxyIdentity {
        Objects.requireNonNull(proxyId, "proxyId");
        Objects.requireNonNull(proxyName, "proxyName");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(version, "version");
        host = host == null ? "" : host;
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
