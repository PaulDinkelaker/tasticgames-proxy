package de.tasticgames.proxy.chat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.social.clan.ClanService;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hält das Clan-Kürzel jedes Spielers für die Chatzeile bereit.
 *
 * <p>Chat ist die häufigste Aktion auf dem Netzwerk – eine API-Abfrage je Nachricht käme nicht in
 * Frage. Deshalb ein Zwischenspeicher: beim Login geladen, danach höchstens alle
 * {@link #TTL} erneuert und beim Verlassen vergessen. Ist noch nichts geladen, erscheint die
 * Nachricht einmal ohne Kürzel, statt auf die API zu warten – die nächste hat es dann.</p>
 */
public final class ClanTagService implements ProxyService {

    /** Wie lange ein Eintrag gilt, bevor er im Hintergrund erneuert wird. */
    static final Duration TTL = Duration.ofMinutes(2);

    private record Entry(ClanTag tag, long loadedAt) {
    }

    private final ClanService clans;
    private final Logger logger;
    private final ConcurrentMap<UUID, Entry> tags = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loading = ConcurrentHashMap.newKeySet();

    public ClanTagService(ClanService clans, Logger logger) {
        this.clans = java.util.Objects.requireNonNull(clans, "clans");
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "clan-tag-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        tags.clear();
        loading.clear();
    }

    /** Das Kürzel des Spielers; leer, wenn er in keinem Clan ist oder noch nichts geladen wurde. */
    public Optional<ClanTag> tagOf(Player player) {
        UUID uuid = player.getUniqueId();
        Entry entry = tags.get(uuid);
        if (entry == null || System.currentTimeMillis() - entry.loadedAt() > TTL.toMillis()) {
            load(uuid);
        }
        return entry == null || entry.tag().isEmpty() ? Optional.empty() : Optional.of(entry.tag());
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        load(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** Lädt im Hintergrund; ein Fehlschlag heißt nur, dass die Zeile vorerst ohne Kürzel steht. */
    public void load(UUID player) {
        if (!loading.add(player)) {
            return;
        }
        clans.clanOf(player).whenComplete((clan, throwable) -> {
            loading.remove(player);
            if (throwable != null) {
                logger.debug("Clan lookup for {} failed: {}", player, Throwables.rootMessage(throwable));
                return;
            }
            ClanTag tag = clan.map(response -> ClanTag.of(response.name())).orElseGet(() -> ClanTag.of(""));
            tags.put(player, new Entry(tag, System.currentTimeMillis()));
        });
    }

    /** Nach einem Clan-Wechsel: beim nächsten Blick neu laden. */
    public void invalidate(UUID player) {
        tags.remove(player);
    }

    public void forget(UUID player) {
        tags.remove(player);
        loading.remove(player);
    }
}
