package de.tasticgames.proxy.util;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation helpers for identifiers coming from players, config or the API.
 */
public final class Ids {

    private static final Pattern SERVER_ID = Pattern.compile("^[a-z0-9._-]{1,64}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern CLAN_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private Ids() {
    }

    public static String serverId(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SERVER_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid server id: " + value);
        }
        return normalized;
    }

    public static boolean isServerId(String value) {
        return value != null && SERVER_ID.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public static boolean isUsername(String value) {
        return value != null && USERNAME.matcher(value.trim()).matches();
    }

    public static boolean isClanName(String value) {
        return value != null && CLAN_NAME.matcher(value.trim()).matches();
    }

    public static Optional<UUID> parseUuid(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 32) {
                trimmed = trimmed.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }
            return Optional.of(UUID.fromString(trimmed));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
