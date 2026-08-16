package de.tasticgames.proxy.locale;

import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches the TasticGames account language per online player. Loaded asynchronously on
 * login; until then (and for unknown accounts) the client locale, then English is used.
 */
public final class PlayerLanguageService implements ProxyService {

    private final ProxyApiClient apiClient;
    private final Logger logger;
    private final ConcurrentMap<UUID, ProxyLanguage> languages = new ConcurrentHashMap<>();

    public PlayerLanguageService(ProxyApiClient apiClient, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "player-language-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        languages.clear();
    }

    public ProxyLanguage languageOf(Player player) {
        ProxyLanguage cached = languages.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        return ProxyLanguage.find(player.getEffectiveLocale()).orElse(ProxyLanguage.ENGLISH);
    }

    public Optional<ProxyLanguage> cached(UUID uuid) {
        return Optional.ofNullable(languages.get(uuid));
    }

    /** Best-effort async load; failures only mean the client locale is used. */
    public void load(UUID uuid) {
        if (!apiClient.enabled()) {
            return;
        }
        apiClient.call("player.language", client -> client.network().findPlayer(uuid))
                .whenComplete((account, throwable) -> {
                    if (throwable != null) {
                        logger.debug("Language lookup for {} failed: {}", uuid, Throwables.rootMessage(throwable));
                        return;
                    }
                    account.flatMap(a -> ProxyLanguage.find(a.language()))
                            .ifPresent(language -> languages.put(uuid, language));
                });
    }

    public void set(UUID uuid, ProxyLanguage language) {
        languages.put(uuid, language);
    }

    public void forget(UUID uuid) {
        languages.remove(uuid);
    }
}
