package de.tasticgames.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.bootstrap.ProxyBootstrap;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

/**
 * TasticProxy – network control plane of TasticGames.de (Velocity plugin entry point).
 */
@Plugin(
        id = "tasticproxy",
        name = "TasticProxy",
        version = "1.0.0",
        description = "Network platform plugin for the TasticGames network.",
        url = "https://tasticgames.de",
        authors = {"PaulDinkelaker"}
)
public final class TasticProxyPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private volatile ProxyBootstrap bootstrap;

    @Inject
    public TasticProxyPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("Starting TasticProxy...");
        try {
            ProxyBootstrap newBootstrap = new ProxyBootstrap(this, proxyServer, logger, dataDirectory);
            newBootstrap.start();
            bootstrap = newBootstrap;
            logger.info("TasticProxy started successfully.");
        } catch (Exception exception) {
            logger.error("TasticProxy failed to start. The proxy keeps running WITHOUT network features – fix the configuration and restart.", exception);
            bootstrap = null;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ProxyBootstrap current = bootstrap;
        if (current == null) {
            return;
        }
        logger.info("Stopping TasticProxy...");
        try {
            current.stop();
            logger.info("TasticProxy stopped successfully.");
        } catch (Exception exception) {
            logger.error("TasticProxy failed to stop cleanly.", exception);
        } finally {
            bootstrap = null;
        }
    }

    public ProxyServer proxyServer() {
        return proxyServer;
    }

    public Logger logger() {
        return logger;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public ProxyBootstrap bootstrap() {
        ProxyBootstrap current = bootstrap;
        if (current == null || !current.isRunning()) {
            throw new IllegalStateException("TasticProxy bootstrap is not running.");
        }
        return current;
    }
}
