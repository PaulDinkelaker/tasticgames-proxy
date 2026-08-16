package de.tasticgames.proxy.locale;

import java.util.Locale;
import java.util.Optional;

/**
 * Languages supported by proxy-level messages (network messages are shown before any
 * backend plugin is involved, hence the proxy needs its own bundles).
 */
public enum ProxyLanguage {
    ENGLISH("en"),
    GERMAN("de");

    private final String code;

    ProxyLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ProxyLanguage> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = normalized.indexOf('-');
        if (dash > 0) {
            normalized = normalized.substring(0, dash);
        }
        for (ProxyLanguage language : values()) {
            if (language.code.equals(normalized)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    public static Optional<ProxyLanguage> find(Locale locale) {
        return locale == null ? Optional.empty() : find(locale.getLanguage());
    }
}
