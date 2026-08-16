package de.tasticgames.proxy.social;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.Ids;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Name/UUID resolution for social commands: online players on this proxy first (no I/O),
 * then the TasticGames account database (exact, case-insensitive; never fuzzy).
 */
public final class SocialPlayerLookup implements ProxyService {

    private final ProxyServer proxyServer;
    private final ProxyApiClient apiClient;

    public SocialPlayerLookup(ProxyServer proxyServer, ProxyApiClient apiClient) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    @Override
    public String id() {
        return "social-player-lookup";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public CompletableFuture<Optional<PlayerRef>> byNameOrUuid(String input) {
        if (input == null || input.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<UUID> uuid = Ids.parseUuid(input);
        if (uuid.isPresent()) {
            return byUuid(uuid.get());
        }
        if (!Ids.isUsername(input)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<PlayerRef> local = proxyServer.getPlayer(input.trim())
                .map(player -> new PlayerRef(player.getUniqueId(), player.getUsername()));
        if (local.isPresent() || !apiClient.enabled()) {
            return CompletableFuture.completedFuture(local);
        }
        return apiClient.call("player.byName", client -> client.network().findPlayerByName(input.trim()))
                .thenApply(account -> account.map(a -> new PlayerRef(a.minecraftUuid(), a.currentName())));
    }

    public CompletableFuture<Optional<PlayerRef>> byUuid(UUID uuid) {
        Optional<PlayerRef> local = proxyServer.getPlayer(uuid).map(p -> new PlayerRef(p.getUniqueId(), p.getUsername()));
        if (local.isPresent() || !apiClient.enabled()) {
            return CompletableFuture.completedFuture(local);
        }
        return apiClient.call("player.byUuid", client -> client.network().findPlayer(uuid))
                .thenApply(account -> account.map(a -> new PlayerRef(a.minecraftUuid(), a.currentName())));
    }
}
