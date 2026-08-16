package de.tasticgames.proxy.locale;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import de.tasticgames.proxy.service.ProxyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Proxy-level localization: MiniMessage bundles for English (fallback) and German loaded
 * from {@code messages/proxy_<lang>.properties}. Player language is resolved via
 * {@link PlayerLanguageService} (TasticGames account language, then client locale).
 */
public final class ProxyMessages implements ProxyService {

    private final Logger logger;
    private final Function<Player, ProxyLanguage> languageResolver;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<ProxyLanguage, Properties> bundles = new EnumMap<>(ProxyLanguage.class);

    public ProxyMessages(Logger logger, Function<Player, ProxyLanguage> languageResolver) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.languageResolver = Objects.requireNonNull(languageResolver, "languageResolver");
    }

    @Override
    public String id() {
        return "proxy-messages";
    }

    @Override
    public void start() throws IOException {
        for (ProxyLanguage language : ProxyLanguage.values()) {
            bundles.put(language, load(language));
        }
        Properties english = bundles.get(ProxyLanguage.ENGLISH);
        List<String> missing = new ArrayList<>();
        for (ProxyLanguage language : ProxyLanguage.values()) {
            if (language == ProxyLanguage.ENGLISH) {
                continue;
            }
            Properties bundle = bundles.get(language);
            for (String key : english.stringPropertyNames()) {
                if (!bundle.containsKey(key)) {
                    missing.add(language.code() + ":" + key);
                }
            }
        }
        if (!missing.isEmpty()) {
            logger.warn("Proxy message bundles are missing {} translation(s) (English fallback is used): {}",
                    missing.size(), missing.size() > 10 ? missing.subList(0, 10) + "..." : missing);
        }
        logger.info("Loaded proxy messages: {} keys, {} languages.", english.size(), bundles.size());
    }

    @Override
    public void stop() {
        bundles.clear();
    }

    public Component get(ProxyLanguage language, String key, Map<String, ?> placeholders) {
        String raw = raw(language, key);
        TagResolver.Builder resolvers = TagResolver.builder();
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Component component) {
                resolvers.resolver(Placeholder.component(entry.getKey(), component));
            } else {
                resolvers.resolver(Placeholder.unparsed(entry.getKey(), String.valueOf(value)));
            }
        }
        return miniMessage.deserialize(raw, resolvers.build());
    }

    public Component get(ProxyLanguage language, String key) {
        return get(language, key, Map.of());
    }

    public Component get(CommandSource source, String key) {
        return get(languageOf(source), key, Map.of());
    }

    public Component get(CommandSource source, String key, Map<String, ?> placeholders) {
        return get(languageOf(source), key, placeholders);
    }

    public void send(CommandSource source, String key) {
        source.sendMessage(get(source, key));
    }

    public void send(CommandSource source, String key, Map<String, ?> placeholders) {
        source.sendMessage(get(source, key, placeholders));
    }

    public ProxyLanguage languageOf(CommandSource source) {
        if (source instanceof Player player) {
            return languageResolver.apply(player);
        }
        return ProxyLanguage.ENGLISH;
    }

    public String raw(ProxyLanguage language, String key) {
        Properties bundle = bundles.get(language);
        String value = bundle == null ? null : bundle.getProperty(key);
        if (value == null) {
            value = bundles.getOrDefault(ProxyLanguage.ENGLISH, new Properties()).getProperty(key);
        }
        if (value == null) {
            logger.warn("Missing proxy message key: {}", key);
            return "<red>[" + key + "]";
        }
        return value;
    }

    public boolean contains(String key) {
        Properties english = bundles.get(ProxyLanguage.ENGLISH);
        return english != null && english.containsKey(key);
    }

    private Properties load(ProxyLanguage language) throws IOException {
        String resource = "messages/proxy_" + language.code() + ".properties";
        Properties properties = new Properties();
        try (InputStream in = ProxyMessages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                if (language == ProxyLanguage.ENGLISH) {
                    throw new IOException("Missing required message bundle: " + resource);
                }
                logger.warn("Message bundle {} not found – falling back to English.", resource);
                return properties;
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
