package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.server.NetworkServer;

import java.util.Objects;

/**
 * One evaluated server: either eligible with a score (higher is better) or excluded with a reason.
 */
public record RoutingCandidate(
        NetworkServer server,
        boolean eligible,
        String exclusionReason,
        double score,
        String scoreExplanation
) {
    public RoutingCandidate {
        Objects.requireNonNull(server, "server");
        exclusionReason = exclusionReason == null ? "" : exclusionReason;
        scoreExplanation = scoreExplanation == null ? "" : scoreExplanation;
    }

    public static RoutingCandidate excluded(NetworkServer server, String reason) {
        return new RoutingCandidate(server, false, reason, Double.NEGATIVE_INFINITY, "");
    }

    public static RoutingCandidate eligible(NetworkServer server, double score, String explanation) {
        return new RoutingCandidate(server, true, "", score, explanation);
    }
}
