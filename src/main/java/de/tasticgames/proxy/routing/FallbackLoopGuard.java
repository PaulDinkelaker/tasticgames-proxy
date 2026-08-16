package de.tasticgames.proxy.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects fallback loops: more than {@code maxInWindow} fallbacks of one player within
 * {@code window} means the player bounces between failing servers and must be disconnected.
 */
public final class FallbackLoopGuard {

    private final Duration window;
    private final int maxInWindow;
    private final ConcurrentHashMap<UUID, ArrayDeque<Instant>> history = new ConcurrentHashMap<>();

    public FallbackLoopGuard(Duration window, int maxInWindow) {
        this.window = Objects.requireNonNull(window, "window");
        this.maxInWindow = maxInWindow;
    }

    /** Records a fallback and returns true when the loop threshold is exceeded. */
    public boolean recordAndDetectLoop(UUID uuid) {
        Instant now = Instant.now();
        ArrayDeque<Instant> deque = history.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(now.minus(window))) {
                deque.pollFirst();
            }
            return deque.size() > maxInWindow;
        }
    }

    public void forget(UUID uuid) {
        history.remove(uuid);
    }

    public void clear() {
        history.clear();
    }
}
