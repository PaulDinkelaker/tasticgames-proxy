package de.tasticgames.proxy.routing;

import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.ProxyScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Short-lived local capacity reservations so that two concurrently planned transfers
 * (e.g. two parties) cannot both take the last free slots. Reservations expire
 * automatically; the transfer that materialises releases them explicitly.
 */
public final class CapacityReservationService implements ProxyService {

    private record Reservation(UUID id, String serverId, int slots, Instant expiresAt) {
    }

    private final java.util.function.Supplier<Duration> ttl;
    private final ProxyScheduler scheduler;
    private final ConcurrentMap<UUID, Reservation> reservations = new ConcurrentHashMap<>();

    public CapacityReservationService(ProxyConfigurationService configurationService, ProxyScheduler scheduler) {
        Objects.requireNonNull(configurationService, "configurationService");
        this.ttl = () -> configurationService.configuration().routing().reservationTimeout();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Test/embedded constructor with a fixed reservation TTL and no scheduler (expiry on access). */
    CapacityReservationService(Duration reservationTtl) {
        Objects.requireNonNull(reservationTtl, "reservationTtl");
        this.ttl = () -> reservationTtl;
        this.scheduler = null;
    }

    @Override
    public String id() {
        return "capacity-reservation-service";
    }

    @Override
    public void start() {
        if (scheduler != null) {
            scheduler.repeat("capacity-reservation-expiry", Duration.ofSeconds(5), Duration.ofSeconds(5), this::expire);
        }
    }

    @Override
    public void stop() {
        reservations.clear();
    }

    /** Reserved (non-expired) slots for a server. */
    public int reserved(String serverId) {
        Instant now = Instant.now();
        int total = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.serverId.equals(serverId) && reservation.expiresAt.isAfter(now)) {
                total += reservation.slots;
            }
        }
        return total;
    }

    /**
     * Atomically reserves {@code slots} on the server when {@code freeSlots - reserved >= slots}.
     * @return the reservation id or null when the capacity is exhausted
     */
    public synchronized UUID reserve(String serverId, int slots, int freeSlots) {
        Objects.requireNonNull(serverId, "serverId");
        if (slots < 1) {
            throw new IllegalArgumentException("slots must be >= 1");
        }
        if (freeSlots - reserved(serverId) < slots) {
            return null;
        }
        UUID id = UUID.randomUUID();
        reservations.put(id, new Reservation(id, serverId, slots, Instant.now().plus(ttl.get())));
        return id;
    }

    public void release(UUID reservationId) {
        if (reservationId != null) {
            reservations.remove(reservationId);
        }
    }

    public int activeReservations() {
        expire();
        return reservations.size();
    }

    void expire() {
        Instant now = Instant.now();
        reservations.values().removeIf(r -> !r.expiresAt.isAfter(now));
    }

    Map<UUID, String> snapshot() {
        Map<UUID, String> out = new java.util.HashMap<>();
        reservations.forEach((id, r) -> out.put(id, r.serverId + ":" + r.slots));
        return out;
    }
}
