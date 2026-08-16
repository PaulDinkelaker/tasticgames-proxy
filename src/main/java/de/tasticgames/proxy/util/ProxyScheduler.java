package de.tasticgames.proxy.util;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tasticgames.proxy.service.ProxyService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the Velocity scheduler plus a dedicated async pool for API I/O.
 * Every task is tracked so shutdown can cancel everything deterministically.
 * Exceptions thrown by tasks are logged instead of silently killing the repeating task.
 */
public final class ProxyScheduler implements ProxyService {

    private final Object plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService asyncPool;

    public ProxyScheduler(Object plugin, ProxyServer proxyServer, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "proxy-scheduler";
    }

    @Override
    public void start() {
        asyncPool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "TasticProxy-Async");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void stop() {
        for (ScheduledTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        ExecutorService pool = asyncPool;
        asyncPool = null;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public ScheduledTask repeat(String name, Duration initialDelay, Duration period, Runnable runnable) {
        ScheduledTask task = proxyServer.getScheduler()
                .buildTask(plugin, guarded(name, runnable))
                .delay(initialDelay)
                .repeat(period)
                .schedule();
        tasks.add(task);
        return task;
    }

    public ScheduledTask later(String name, Duration delay, Runnable runnable) {
        ScheduledTask task = proxyServer.getScheduler()
                .buildTask(plugin, guarded(name, runnable))
                .delay(delay)
                .schedule();
        tasks.add(task);
        return task;
    }

    public void async(String name, Runnable runnable) {
        ExecutorService pool = asyncPool;
        if (pool == null) {
            throw new IllegalStateException("Scheduler is not running.");
        }
        pool.execute(guarded(name, runnable));
    }

    public java.util.concurrent.Executor asyncExecutor() {
        ExecutorService pool = asyncPool;
        if (pool == null) {
            throw new IllegalStateException("Scheduler is not running.");
        }
        return pool;
    }

    public void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
            tasks.remove(task);
        }
    }

    public int activeTasks() {
        return tasks.size();
    }

    private Runnable guarded(String name, Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                logger.error("Scheduled task '{}' failed: {}", name, Throwables.rootMessage(throwable), throwable);
            }
        };
    }
}
