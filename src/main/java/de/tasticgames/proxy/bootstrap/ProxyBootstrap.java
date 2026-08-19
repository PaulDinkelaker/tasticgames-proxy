package de.tasticgames.proxy.bootstrap;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tasticgames.proxy.TasticProxyPlugin;
import de.tasticgames.proxy.access.AlphaAccessListener;
import de.tasticgames.proxy.access.AlphaAccessService;
import de.tasticgames.proxy.api.ProxyApiClient;
import de.tasticgames.proxy.bus.ApiNetworkCommandBus;
import de.tasticgames.proxy.bus.CommandTypes;
import de.tasticgames.proxy.bus.NetworkCommand;
import de.tasticgames.proxy.bus.NetworkCommandBus;
import de.tasticgames.proxy.command.AlphaAccessCommand;
import de.tasticgames.proxy.command.ClanCommand;
import de.tasticgames.proxy.command.FriendCommand;
import de.tasticgames.proxy.command.LobbyCommand;
import de.tasticgames.proxy.command.MaintenanceCommand;
import de.tasticgames.proxy.command.PartyCommand;
import de.tasticgames.proxy.command.PassAdminCommand;
import de.tasticgames.proxy.command.ServerStatusCommand;
import de.tasticgames.proxy.command.TasticProxyCommand;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyBuildInfo;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.identity.ProxyIdentity;
import de.tasticgames.proxy.identity.ProxyRegistryService;
import de.tasticgames.proxy.locale.PlayerLanguageService;
import de.tasticgames.proxy.locale.ProxyMessages;
import de.tasticgames.proxy.maintenance.MaintenanceListener;
import de.tasticgames.proxy.maintenance.MaintenancePingListener;
import de.tasticgames.proxy.maintenance.MaintenanceService;
import de.tasticgames.proxy.pass.PassService;
import de.tasticgames.proxy.player.NetworkPlayerListener;
import de.tasticgames.proxy.player.NetworkPlayerManager;
import de.tasticgames.proxy.player.NetworkPlayerSession;
import de.tasticgames.proxy.presence.NetworkPresenceService;
import de.tasticgames.proxy.routing.CapacityReservationService;
import de.tasticgames.proxy.routing.FallbackListener;
import de.tasticgames.proxy.routing.FallbackService;
import de.tasticgames.proxy.routing.RoutingGuardListener;
import de.tasticgames.proxy.routing.RoutingService;
import de.tasticgames.proxy.routing.TransferReason;
import de.tasticgames.proxy.routing.TransferService;
import de.tasticgames.proxy.server.DrainListener;
import de.tasticgames.proxy.server.DrainService;
import de.tasticgames.proxy.server.ServerHealthService;
import de.tasticgames.proxy.server.ServerRegistryListener;
import de.tasticgames.proxy.server.ServerRegistryService;
import de.tasticgames.proxy.server.ServerType;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.service.ProxyServiceRegistry;
import de.tasticgames.proxy.social.NetworkNotificationService;
import de.tasticgames.proxy.social.RateLimiter;
import de.tasticgames.proxy.social.SocialPlayerLookup;
import de.tasticgames.proxy.social.clan.ClanService;
import de.tasticgames.proxy.social.friend.FriendService;
import de.tasticgames.proxy.social.party.PartyService;
import de.tasticgames.proxy.social.party.PartyTransferService;
import de.tasticgames.proxy.telemetry.TelemetryService;
import de.tasticgames.proxy.telemetry.TelemetryTypes;
import de.tasticgames.proxy.util.ProxyScheduler;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composition root and lifecycle coordinator. Services are started in dependency order and
 * stopped in reverse; a failed start stops everything that was already started.
 */
public final class ProxyBootstrap {

    private final TasticProxyPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ProxyServiceRegistry serviceRegistry = new ProxyServiceRegistry();
    private final Deque<ProxyService> started = new ArrayDeque<>();
    private final List<Object> listeners = new ArrayList<>();
    private final List<CommandMeta> commands = new ArrayList<>();

    private ProxyBuildInfo buildInfo;
    private ProxyIdentity identity;
    private ProxyConfigurationService configurationService;
    private ProxyMetrics metrics;
    private ProxyScheduler scheduler;
    private ProxyApiClient apiClient;
    private PlayerLanguageService languageService;
    private ProxyMessages messages;
    private TelemetryService telemetryService;
    private ProxyRegistryService proxyRegistryService;
    private ServerRegistryService serverRegistryService;
    private ServerHealthService serverHealthService;
    private MaintenanceService maintenanceService;
    private AlphaAccessService alphaAccessService;
    private NetworkPlayerManager playerManager;
    private NetworkPresenceService presenceService;
    private CapacityReservationService reservationService;
    private RoutingService routingService;
    private TransferService transferService;
    private FallbackService fallbackService;
    private DrainService drainService;
    private ApiNetworkCommandBus commandBus;
    private NetworkNotificationService notificationService;
    private SocialPlayerLookup playerLookup;
    private FriendService friendService;
    private PartyService partyService;
    private PartyTransferService partyTransferService;
    private ClanService clanService;
    private PassService passService;
    private NetworkPlayerListener playerListener;

    public ProxyBootstrap(TasticProxyPlugin plugin, ProxyServer proxyServer, Logger logger, Path dataDirectory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("TasticProxy bootstrap is already running.");
        }
        long startNanos = System.nanoTime();
        try {
            buildInfo = ProxyBuildInfo.load();
            configurationService = start(new ProxyConfigurationService(dataDirectory, logger), ProxyConfigurationService.class);
            ProxyConfiguration config = configurationService.configuration();
            identity = new ProxyIdentity(config.identity().proxyId(), config.identity().proxyName(), config.identity().region(),
                    config.identity().environment(), buildInfo.version(), config.identity().host(), Instant.now());

            metrics = start(new ProxyMetrics(), ProxyMetrics.class);
            scheduler = start(new ProxyScheduler(plugin, proxyServer, logger), ProxyScheduler.class);
            apiClient = start(new ProxyApiClient(configurationService, metrics, logger), ProxyApiClient.class);
            verifyApiOnStartup(config);

            languageService = start(new PlayerLanguageService(apiClient, logger), PlayerLanguageService.class);
            messages = start(new ProxyMessages(logger, languageService::languageOf), ProxyMessages.class);
            telemetryService = start(new TelemetryService(configurationService, apiClient, scheduler, metrics, identity, logger), TelemetryService.class);
            proxyRegistryService = start(new ProxyRegistryService(proxyServer, identity, configurationService, apiClient, scheduler,
                    telemetryService, metrics, logger), ProxyRegistryService.class);

            serverRegistryService = start(new ServerRegistryService(proxyServer, configurationService, apiClient, scheduler,
                    telemetryService, identity, logger), ServerRegistryService.class);
            serverHealthService = start(new ServerHealthService(serverRegistryService, configurationService, apiClient, scheduler,
                    telemetryService, metrics, identity, logger), ServerHealthService.class);
            maintenanceService = start(new MaintenanceService(proxyServer, configurationService, apiClient, scheduler, telemetryService,
                    messages, dataDirectory, logger), MaintenanceService.class);
            alphaAccessService = start(new AlphaAccessService(configurationService, apiClient, scheduler, telemetryService, messages,
                    dataDirectory, logger), AlphaAccessService.class);

            playerManager = start(new NetworkPlayerManager(identity), NetworkPlayerManager.class);
            presenceService = start(new NetworkPresenceService(apiClient, configurationService, playerManager, serverRegistryService,
                    scheduler, metrics, identity, logger), NetworkPresenceService.class);
            reservationService = start(new CapacityReservationService(configurationService, scheduler), CapacityReservationService.class);
            routingService = start(new RoutingService(serverRegistryService, reservationService, configurationService, telemetryService,
                    metrics, logger), RoutingService.class);
            transferService = start(new TransferService(proxyServer, serverRegistryService, routingService, reservationService, playerManager,
                    configurationService, telemetryService, metrics, messages, scheduler, logger), TransferService.class);
            fallbackService = start(new FallbackService(configurationService, routingService, messages, telemetryService, metrics, logger), FallbackService.class);
            drainService = start(new DrainService(serverRegistryService, transferService, scheduler, telemetryService, logger), DrainService.class);

            commandBus = start(new ApiNetworkCommandBus(proxyServer, apiClient, configurationService, scheduler, metrics, identity, logger), ApiNetworkCommandBus.class);
            notificationService = start(new NetworkNotificationService(proxyServer, commandBus, messages, logger), NetworkNotificationService.class);
            playerLookup = start(new SocialPlayerLookup(proxyServer, apiClient), SocialPlayerLookup.class);
            RateLimiter rateLimiter = new RateLimiter();
            friendService = start(new FriendService(apiClient, configurationService, presenceService, notificationService, telemetryService,
                    rateLimiter, logger), FriendService.class);
            partyService = start(new PartyService(apiClient, configurationService, presenceService, notificationService, telemetryService,
                    rateLimiter, scheduler, logger), PartyService.class);
            partyTransferService = start(new PartyTransferService(proxyServer, apiClient, partyService, routingService, transferService,
                    reservationService, serverRegistryService, commandBus, notificationService, configurationService, telemetryService,
                    identity, logger), PartyTransferService.class);
            clanService = start(new ClanService(apiClient, configurationService, presenceService, notificationService, telemetryService,
                    rateLimiter, logger), ClanService.class);
            passService = start(new PassService(proxyServer, apiClient, commandBus, notificationService, dataDirectory, logger),
                    PassService.class);
            // one chat for the whole network: the proxy renders every line and forwards it to the other proxies
            chatService = start(new de.tasticgames.proxy.chat.GlobalChatService(proxyServer, commandBus, messages,
                    new de.tasticgames.proxy.chat.PermissionChatIdentity(messages), true, java.time.Duration.ofMillis(750),
                    256, logger), de.tasticgames.proxy.chat.GlobalChatService.class);

            wireBusHandlers();
            registerListeners();
            registerCommands();
            proxyRegistryService.markOnline();
            logger.info("TasticProxy {} bootstrap finished in {} ms ({} services, {} listeners, {} commands).", buildInfo.version(),
                    (System.nanoTime() - startNanos) / 1_000_000, started.size(), listeners.size(), commands.size());
        } catch (Exception exception) {
            logger.error("TasticProxy bootstrap failed – stopping already started services: {}", Throwables.rootMessage(exception));
            running.set(false);
            try {
                stopInternal();
            } catch (Exception stopException) {
                exception.addSuppressed(stopException);
            }
            throw exception;
        }
    }

    public void stop() throws Exception {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        logger.info("Stopping TasticProxy bootstrap...");
        stopInternal();
        logger.info("TasticProxy bootstrap stopped.");
    }

    public boolean isRunning() {
        return running.get();
    }

    public ProxyServiceRegistry serviceRegistry() {
        return serviceRegistry;
    }

    // ------------------------------------------------------------------ wiring

    private void verifyApiOnStartup(ProxyConfiguration config) throws Exception {
        if (!apiClient.enabled()) {
            if (config.api().requireOnStartup()) {
                throw new IllegalStateException("api.require-on-startup=true but the API integration is not configured.");
            }
            return;
        }
        CompletableFuture<?> health = apiClient.call("health", client -> client.health().thenAccept(h ->
                logger.info("API connection established: {} {} [{}].", h.service(), h.version(), h.status())))
                .thenCompose(ignored -> apiClient.call("network.servers", client -> client.network().listServers().thenAccept(servers ->
                        logger.info("API authentication verified for service '{}' ({} network servers registered).",
                                config.api().serviceName(), servers.size()))))
                .exceptionally(t -> {
                    Throwable cause = Throwables.unwrap(t);
                    if (cause instanceof de.tasticgames.client.internal.HttpException http && http.statusCode() == 404) {
                        logger.error("The TasticGames API at {} does not know the 1.0 endpoints (GET /api/v1/network/servers -> 404). "
                                + "Deploy tasticgames-api 1.0 (Flyway V5-V15); until then the proxy runs on last-known-good caches.", config.api().baseUrl());
                        return null;
                    }
                    throw new java.util.concurrent.CompletionException(cause);
                });
        if (config.api().requireOnStartup()) {
            health.get(config.api().requestTimeout().toMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            health.exceptionally(t -> {
                logger.warn("API health check failed – running with last-known-good caches until it recovers: {}", Throwables.rootMessage(t));
                return null;
            });
        }
    }

    private void wireBusHandlers() {
        commandBus.subscribe(CommandTypes.MAINTENANCE_CHANGED, command -> {
            maintenanceService.refresh();
            return CompletableFuture.completedFuture("refreshed");
        });
        commandBus.subscribe(CommandTypes.ALPHA_CHANGED, command -> {
            alphaAccessService.refresh();
            return CompletableFuture.completedFuture("refreshed");
        });
        commandBus.subscribe(CommandTypes.SERVER_STATE_CHANGED, command -> {
            serverRegistryService.synchronize();
            return CompletableFuture.completedFuture("synced");
        });
        commandBus.subscribe(CommandTypes.PLAYER_KICK, command -> {
            Optional<Player> player = command.targetPlayerUuid() == null ? Optional.empty() : proxyServer.getPlayer(command.targetPlayerUuid());
            player.ifPresent(p -> p.disconnect(messages.get(p, command.getOrDefault("key", "kick.generic"), Map.of("reason", command.getOrDefault("reason", "")))));
            return CompletableFuture.completedFuture(player.isPresent() ? "kicked" : "not here");
        });
        commandBus.subscribe(CommandTypes.PLAYER_TRANSFER, this::handleTransferCommand);
        commandBus.subscribe(CommandTypes.PARTY_TRANSFER, this::handleTransferCommand);

        // propagate central changes made on this proxy to the others (they re-pull the central state)
        maintenanceService.onChange(state -> commandBus.broadcast(CommandTypes.MAINTENANCE_CHANGED, Map.of("enabled", String.valueOf(state.enabled()))));
        serverRegistryService.onStateChanged("bus", server -> commandBus.broadcast(CommandTypes.SERVER_STATE_CHANGED,
                Map.of("server", server.serverId(), "state", server.adminState().name())));
    }

    /** Backend (gateway) transfer requests arrive via the API command bus. */
    private CompletableFuture<String> handleTransferCommand(NetworkCommand command) {
        UUID playerUuid = command.targetPlayerUuid() != null ? command.targetPlayerUuid()
                : command.get("playerUuid") != null ? UUID.fromString(command.get("playerUuid")) : null;
        if (playerUuid == null) {
            return CompletableFuture.completedFuture("no player");
        }
        Player player = proxyServer.getPlayer(playerUuid).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture("player not here");
        }
        ServerType type = ServerType.find(command.get("targetType")).orElse(null);
        String serverId = command.get("targetServerId");
        if (type == null && (serverId == null || serverId.isBlank() || serverId.equals("null"))) {
            return CompletableFuture.completedFuture("no target");
        }
        boolean party = CommandTypes.PARTY_TRANSFER.equals(command.type()) || "true".equals(command.get("partyTransfer"));
        if (party) {
            return partyTransferService.transferParty(player, type, serverId == null || serverId.equals("null") ? null : serverId, TransferReason.GATEWAY)
                    .thenApply(outcome -> outcome.state().name() + (outcome.message().isBlank() ? "" : ": " + outcome.message()));
        }
        CompletableFuture<de.tasticgames.proxy.routing.TransferResult> transfer = serverId != null && !serverId.equals("null")
                ? transferService.transferToServer(player, serverId, TransferReason.GATEWAY)
                : transferService.transferToType(player, type, TransferReason.GATEWAY);
        return transfer.thenApply(result -> result.status().name() + (result.message().isBlank() ? "" : ": " + result.message()));
    }

    private de.tasticgames.proxy.chat.GlobalChatService chatService;

    private void registerListeners() {
        playerListener = new NetworkPlayerListener(playerManager, presenceService, routingService, configurationService, languageService,
                fallbackService, messages, telemetryService, metrics, logger);
        playerListener.onJoin(session -> friendService.notifyPresenceChange(session, true));
        playerListener.onQuit(session -> {
            friendService.notifyPresenceChange(session, false);
            partyService.memberOffline(session);
        });
        register(new MaintenanceListener(maintenanceService, telemetryService));
        register(new MaintenancePingListener(maintenanceService));
        register(new AlphaAccessListener(alphaAccessService, telemetryService));
        register(playerListener);
        register(this); // /lobby|hub|l forwarding while on a lobby server
        register(new ServerRegistryListener(serverRegistryService));
        register(new RoutingGuardListener(serverRegistryService, messages));
        register(new FallbackListener(fallbackService, logger));
        register(new DrainListener(drainService));
        register(chatService);
        logger.info("Registered {} proxy listeners.", listeners.size());
    }

    private void register(Object listener) {
        proxyServer.getEventManager().register(plugin, listener);
        listeners.add(listener);
    }

    /**
     * Velocity executes registered commands itself and never forwards them: /lobby, /hub and /l are
     * therefore forwarded to the backend while the player already is on a lobby server so
     * TasticLobby's own /lobby (spawn / leave the cookie open world) runs.
     */
    @com.velocitypowered.api.event.Subscribe(order = com.velocitypowered.api.event.PostOrder.EARLY)
    public void onCommandExecute(com.velocitypowered.api.event.command.CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        String raw = event.getCommand().trim();
        int space = raw.indexOf(' ');
        String name = (space < 0 ? raw : raw.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
        if (!(name.equals("lobby") || name.equals("hub") || name.equals("l"))) {
            return;
        }
        String current = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        if (current == null) {
            return;
        }
        boolean onLobby = serverRegistryService.find(current).map(s -> s.type() == de.tasticgames.proxy.server.ServerType.LOBBY).orElse(false);
        if (onLobby) {
            event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.forwardToServer());
        }
    }

    private void registerCommands() {
        TasticProxyCommand.Dependencies deps = new TasticProxyCommand.Dependencies(proxyServer, identity, buildInfo, configurationService,
                apiClient, metrics, proxyRegistryService, serverRegistryService, playerManager, presenceService, routingService, transferService,
                reservationService, commandBus, telemetryService, maintenanceService, alphaAccessService, partyService, clanService, playerLookup,
                this::applyReload);
        command(new TasticProxyCommand(deps, messages, logger), "tasticproxy", "tproxy");
        command(new ServerStatusCommand(serverRegistryService, drainService, serverHealthService, messages, telemetryService, logger), "serverstatus", "servers");
        command(new MaintenanceCommand(maintenanceService, messages, telemetryService, logger), "maintenance");
        command(new AlphaAccessCommand(alphaAccessService, playerLookup, apiClient, messages, telemetryService, logger), "alphaaccess", "alpha");
        command(new LobbyCommand(transferService, messages, telemetryService, logger), "lobby", "hub", "l");
        command(new FriendCommand(friendService, playerLookup, proxyServer, messages, telemetryService, logger), "friend", "friends", "f");
        command(new PartyCommand(partyService, partyTransferService, notificationService, playerLookup, proxyServer, messages, telemetryService, logger, false), "party", "p");
        command(new PartyCommand(partyService, partyTransferService, notificationService, playerLookup, proxyServer, messages, telemetryService, logger, true), "pc", "partychat");
        command(new ClanCommand(clanService, playerLookup, proxyServer, messages, telemetryService, logger), "clan", "c");
        // no /pass, /battlepass or /bp here: registering them would permanently shadow TasticLobby's own /pass
        command(new PassAdminCommand(passService, playerLookup, proxyServer, messages, telemetryService, logger), "passadmin");
        logger.info("Registered {} proxy commands.", commands.size());
    }

    private void command(SimpleCommand command, String name, String... aliases) {
        CommandManager manager = proxyServer.getCommandManager();
        CommandMeta meta = manager.metaBuilder(name).aliases(aliases).plugin(plugin).build();
        manager.register(meta, command);
        commands.add(meta);
    }

    /** Applies reloadable configuration to running services. */
    private void applyReload() {
        serverRegistryService.applyConfiguration();
        logger.info("Configuration reload applied.");
    }

    private <T extends ProxyService> T start(T service, Class<T> type) throws Exception {
        service.start();
        started.push(service);
        serviceRegistry.register(type, service);
        logger.debug("Started service {}.", service.id());
        return service;
    }

    private void stopInternal() throws Exception {
        Exception failure = null;
        CommandManager manager = proxyServer.getCommandManager();
        for (CommandMeta meta : commands) {
            manager.unregister(meta);
        }
        commands.clear();
        proxyServer.getEventManager().unregisterListeners(plugin);
        listeners.clear();

        // best effort: mark local players offline centrally before the presence service stops
        if (presenceService != null && playerManager != null) {
            for (NetworkPlayerSession session : playerManager.onlinePlayers()) {
                presenceService.disconnected(session);
            }
        }
        if (telemetryService != null) {
            telemetryService.publish(telemetryService.event(TelemetryTypes.PROXY_STOPPED).build());
        }
        while (!started.isEmpty()) {
            ProxyService service = started.pop();
            try {
                service.stop();
            } catch (Exception e) {
                logger.warn("Service {} failed to stop cleanly: {}", service.id(), Throwables.rootMessage(e));
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        serviceRegistry.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
