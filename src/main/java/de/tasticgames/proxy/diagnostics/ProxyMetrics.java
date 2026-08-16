package de.tasticgames.proxy.diagnostics;

import de.tasticgames.proxy.service.ProxyService;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-process counters/gauges shown by {@code /tasticproxy status}.
 * Intentionally not a metrics platform.
 */
public final class ProxyMetrics implements ProxyService {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private volatile Instant startedAt = Instant.now();

    @Override
    public String id() {
        return "proxy-metrics";
    }

    @Override
    public void start() {
        startedAt = Instant.now();
    }

    @Override
    public void stop() {
        counters.clear();
        gauges.clear();
    }

    public void increment(String name) {
        counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
    }

    public void add(String name, long delta) {
        counters.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(delta);
    }

    public void gauge(String name, long value) {
        gauges.computeIfAbsent(name, ignored -> new AtomicLong()).set(value);
    }

    public long counter(String name) {
        AtomicLong value = counters.get(name);
        return value == null ? 0 : value.get();
    }

    public long gaugeValue(String name) {
        AtomicLong value = gauges.get(name);
        return value == null ? 0 : value.get();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new TreeMap<>();
        counters.forEach((k, v) -> snapshot.put(k, v.get()));
        gauges.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }
}
