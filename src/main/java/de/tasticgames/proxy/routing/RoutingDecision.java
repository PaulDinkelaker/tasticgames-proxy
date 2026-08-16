package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.server.NetworkServer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a routing evaluation including every candidate for diagnostics.
 */
public record RoutingDecision(
        RoutingRequest request,
        NetworkServer target,
        List<RoutingCandidate> candidates,
        String summary
) {
    public RoutingDecision {
        Objects.requireNonNull(request, "request");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        summary = summary == null ? "" : summary;
    }

    public Optional<NetworkServer> targetOptional() {
        return Optional.ofNullable(target);
    }

    public boolean found() {
        return target != null;
    }

    public List<RoutingCandidate> eligible() {
        return candidates.stream().filter(RoutingCandidate::eligible).toList();
    }

    public List<RoutingCandidate> excluded() {
        return candidates.stream().filter(c -> !c.eligible()).toList();
    }
}
