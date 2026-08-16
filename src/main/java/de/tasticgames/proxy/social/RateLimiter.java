package de.tasticgames.proxy.social;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter per (player, action). Local abuse protection only.
 */
public final class RateLimiter {

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /** @return true when the action is allowed (and recorded) */
    public boolean tryAcquire(UUID player, String action, int limit, Duration window) {
        String key = player + ":" + action;
        Deque<Instant> deque = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant now = Instant.now();
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(now.minus(window))) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** Simple cooldown: allowed when the last acquisition is older than the cooldown. */
    public boolean tryCooldown(UUID player, String action, Duration cooldown) {
        if (cooldown.isZero()) {
            return true;
        }
        return tryAcquire(player, action, 1, cooldown);
    }

    public void forget(UUID player) {
        windows.keySet().removeIf(key -> key.startsWith(player.toString()));
    }
}
