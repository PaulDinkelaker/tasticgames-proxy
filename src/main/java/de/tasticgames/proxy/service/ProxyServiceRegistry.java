package de.tasticgames.proxy.service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ProxyServiceRegistry {

    private final ConcurrentMap<Class<?>, ProxyService> services =
            new ConcurrentHashMap<>();

    public <T extends ProxyService> void register(
            Class<T> type,
            T service
    ) {
        Objects.requireNonNull(
                type,
                "type"
        );

        Objects.requireNonNull(
                service,
                "service"
        );

        ProxyService previous =
                services.putIfAbsent(
                        type,
                        service
                );

        if (previous != null) {
            throw new IllegalStateException(
                    "Service is already registered: "
                            + type.getName()
            );
        }
    }

    public <T extends ProxyService> Optional<T> find(
            Class<T> type
    ) {
        Objects.requireNonNull(
                type,
                "type"
        );

        ProxyService service =
                services.get(
                        type
                );

        if (service == null) {
            return Optional.empty();
        }

        return Optional.of(
                type.cast(
                        service
                )
        );
    }

    public <T extends ProxyService> T require(
            Class<T> type
    ) {
        return find(
                type
        ).orElseThrow(
                () -> new IllegalStateException(
                        "Service is not registered: "
                                + type.getName()
                )
        );
    }

    public void unregister(
            Class<? extends ProxyService> type
    ) {
        Objects.requireNonNull(
                type,
                "type"
        );

        services.remove(
                type
        );
    }

    public Map<Class<?>, ProxyService> snapshot() {
        return Map.copyOf(
                services
        );
    }

    public int size() {
        return services.size();
    }

    public void clear() {
        services.clear();
    }
}
