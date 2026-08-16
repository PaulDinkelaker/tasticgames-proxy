package de.tasticgames.proxy.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build information generated at build time into {@code build-info.properties}.
 */
public record ProxyBuildInfo(String version, String buildTimestamp, String gitCommit) {

    public static ProxyBuildInfo load() {
        Properties properties = new Properties();
        try (InputStream in = ProxyBuildInfo.class.getClassLoader().getResourceAsStream("build-info.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // fall through to defaults
        }
        return new ProxyBuildInfo(
                properties.getProperty("version", "unknown"),
                properties.getProperty("buildTimestamp", "unknown"),
                properties.getProperty("gitCommit", "unknown"));
    }
}
