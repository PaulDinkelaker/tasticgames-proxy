package de.tasticgames.proxy.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Throwables {

    private Throwables() {
    }

    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String rootMessage(Throwable throwable) {
        Throwable current = unwrap(throwable);
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }
}
