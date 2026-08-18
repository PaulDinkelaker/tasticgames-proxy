package de.tasticgames.proxy.api;

import de.tasticgames.client.LobbyApi;
import de.tasticgames.client.NetworkApi;
import de.tasticgames.client.PassApi;
import de.tasticgames.client.SocialApi;
import de.tasticgames.client.TasticApiClient;
import de.tasticgames.client.config.ApiClientConfiguration;
import de.tasticgames.client.internal.HttpException;
import de.tasticgames.proxy.config.ProxyConfiguration;
import de.tasticgames.proxy.config.ProxyConfigurationService;
import de.tasticgames.proxy.diagnostics.ProxyMetrics;
import de.tasticgames.proxy.service.ProxyService;
import de.tasticgames.proxy.util.Throwables;
import org.slf4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Lifecycle wrapper around the shared {@link TasticApiClient}. Every call goes through
 * {@link #call(String, Function)} which records success/failure metrics, latency and the
 * API availability state used by {@code /tasticproxy status}.
 */
public final class ProxyApiClient implements ProxyService {

    private final ProxyConfigurationService configurationService;
    private final ProxyMetrics metrics;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong lastLatencyMillis = new AtomicLong(-1);
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;
    private volatile String lastFailureMessage = "";
    private volatile boolean enabled;
    private volatile TasticApiClient delegate;

    public ProxyApiClient(ProxyConfigurationService configurationService, ProxyMetrics metrics, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "proxy-api-client";
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Proxy API client is already running.");
        }
        ProxyConfiguration.Api configuration = configurationService.configuration().api();
        if (!configuration.enabled()) {
            enabled = false;
            logger.warn("TasticGames API integration is DISABLED – network state will be local only.");
            return;
        }
        if (!configuration.credentialsConfigured()) {
            enabled = false;
            logger.error("TasticGames API integration is enabled but no API key is configured (config api.authentication.api-key "
                    + "or env TASTIC_API_KEY, service '{}'). Running in DEGRADED local mode: /friend, /party, /clan, central maintenance, "
                    + "alpha access and lobby transfers answer 'unavailable' until the key is set.", configuration.serviceName());
            return;
        }
        ApiClientConfiguration clientConfiguration = ApiClientConfiguration.builder()
                .baseUri(normalizeBaseUri(configuration.baseUrl()))
                .connectTimeout(configuration.connectTimeout())
                .requestTimeout(configuration.requestTimeout())
                .serviceName(configuration.serviceName())
                .apiKey(configuration.apiKey())
                .build();
        delegate = new TasticApiClient(clientConfiguration);
        enabled = true;
        logger.info("TasticGames API client initialized for {} (service {}).", clientConfiguration.baseUri(),
                clientConfiguration.serviceName());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        enabled = false;
        TasticApiClient current = delegate;
        delegate = null;
        if (current != null) {
            current.close();
        }
    }

    public boolean enabled() {
        return enabled && running.get();
    }

    /** True while the last API call succeeded (or nothing was attempted yet). */
    public boolean healthy() {
        return (enabled() && consecutiveFailures.get() == 0) && !credentialsRejected;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long lastLatencyMillis() {
        return lastLatencyMillis.get();
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant lastFailureAt() {
        return lastFailureAt;
    }

    public String lastFailureMessage() {
        return lastFailureMessage;
    }

    public NetworkApi network() {
        return delegate().network();
    }

    public SocialApi social() {
        return delegate().social();
    }

    public LobbyApi lobby() {
        return delegate().lobby();
    }

    public PassApi pass() {
        return delegate().pass();
    }

    private volatile boolean credentialsRejected;
    private volatile long lastAuthErrorLogAt;

    /** True after the API answered 401/403 – the configured service key does not match the API. */
    public boolean credentialsRejected() {
        return credentialsRejected;
    }

    private void recordAuthFailure(String operation, HttpException http) {
        credentialsRejected = true;
        metrics.increment("api.auth_failures");
        recordFailure(operation, http);
        long now = System.currentTimeMillis();
        if (now - lastAuthErrorLogAt > 60_000) {
            lastAuthErrorLogAt = now;
            ProxyConfiguration.Api api = configurationService.configuration().api();
            logger.error("The TasticGames API rejected the credentials of service '{}' (HTTP {} on {}). Register the key as "
                    + "tasticgames.security.service-auth.services.{} on the API or fix api.authentication.api-key / TASTIC_API_KEY.",
                    api.serviceName(), http.statusCode(), operation, api.serviceName());
        }
    }

    public TasticApiClient raw() {
        return delegate();
    }

    /**
     * Executes an API call with metrics and error unwrapping. Fails fast with
     * {@link ApiUnavailableException} when the API integration is disabled.
     */
    public <T> CompletableFuture<T> call(String operation, Function<TasticApiClient, CompletableFuture<T>> invocation) {
        if (!enabled()) {
            return CompletableFuture.failedFuture(new ApiUnavailableException("API integration is disabled."));
        }
        long start = System.nanoTime();
        CompletableFuture<T> future;
        try {
            future = invocation.apply(delegate());
        } catch (RuntimeException e) {
            recordFailure(operation, e);
            return CompletableFuture.failedFuture(e);
        }
        return future.handle((result, throwable) -> {
            long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if (throwable != null) {
                Throwable cause = Throwables.unwrap(throwable);
                if (cause instanceof HttpException http && (http.statusCode() == 401 || http.statusCode() == 403)) {
                    recordAuthFailure(operation, http);
                } else if (cause instanceof HttpException http && http.statusCode() >= 400 && http.statusCode() < 500) {
                    // client-side/business error – the API is reachable
                    recordSuccess(millis);
                    metrics.increment("api.client_errors");
                } else {
                    recordFailure(operation, cause);
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            if (credentialsRejected) {
                credentialsRejected = false;
                logger.info("TasticGames API accepted the credentials again.");
            }
            recordSuccess(millis);
            return result;
        });
    }

    private void recordSuccess(long millis) {
        lastLatencyMillis.set(millis);
        lastSuccessAt = Instant.now();
        metrics.increment("api.successes");
        int failures = consecutiveFailures.getAndSet(0);
        if (failures > 0) {
            logger.info("TasticGames API recovered after {} consecutive failure(s).", failures);
            metrics.increment("api.recoveries");
        }
    }

    private void recordFailure(String operation, Throwable cause) {
        lastFailureAt = Instant.now();
        lastFailureMessage = Throwables.rootMessage(cause);
        metrics.increment("api.failures");
        int failures = consecutiveFailures.incrementAndGet();
        if (failures == 1 || failures % 20 == 0) {
            logger.warn("TasticGames API call '{}' failed ({} consecutive): {}", operation, failures, lastFailureMessage);
        }
    }

    private TasticApiClient delegate() {
        TasticApiClient current = delegate;
        if (current == null || current.isClosed() || !enabled) {
            throw new ApiUnavailableException("API integration is not available.");
        }
        return current;
    }

    private static URI normalizeBaseUri(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("API base URL must use HTTP or HTTPS: " + baseUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("API base URL has no valid host: " + baseUrl);
        }
        return uri;
    }

    /** Thrown when the API integration is disabled or not started. */
    public static final class ApiUnavailableException extends RuntimeException {
        public ApiUnavailableException(String message) {
            super(message);
        }
    }
}
