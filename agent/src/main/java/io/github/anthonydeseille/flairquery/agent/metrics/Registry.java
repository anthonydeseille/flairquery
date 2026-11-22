package io.github.anthonydeseille.flairquery.agent.metrics;

import io.github.anthonydeseille.flairquery.agent.spi.FqListener;
import io.github.anthonydeseille.flairquery.agent.spi.RequestSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory aggregates exposed by the HTTP server. Every map is bounded: when full, new keys are
 * dropped and counted ({@code fq_dropped_total}) instead of growing the application heap.
 */
public final class Registry implements FqListener {

    public static final String OTHER_ENDPOINT = "other";
    static final int MAX_ENDPOINTS = 500;
    static final int MAX_SQL = 5_000;
    static final int MAX_NPLUSONE = 1_000;
    static final int MAX_CALLERS_PER_SQL = 5;

    private final String app;
    private final int nPlusOneThreshold;
    private final Map<String, EndpointStats> endpoints = new ConcurrentHashMap<>();
    private final Map<SqlKey, SqlStats> sql = new ConcurrentHashMap<>();
    private final Map<SqlKey, NPlusOneStats> nPlusOne = new ConcurrentHashMap<>();
    private final LongAdder dropped = new LongAdder();

    public Registry(String app, int nPlusOneThreshold) {
        this.app = app;
        this.nPlusOneThreshold = nPlusOneThreshold;
    }

    public String app() { return app; }
    public int nPlusOneThreshold() { return nPlusOneThreshold; }
    public long dropped() { return dropped.sum(); }

    // ---------------------------------------------------------------- recording

    @Override
    public void onBackgroundQuery(String app, String normalizedSql, String caller, long nanos, boolean failed) {
        EndpointStats ep = endpoint("none");
        ep.queries.increment();
        ep.sqlNanos.add(nanos);
        recordSql("none", normalizedSql, caller, 1, nanos, nanos);
    }

    @Override
    public void onRequest(RequestSummary s) {
        EndpointStats ep = endpoint(s.endpoint());
        ep.requests.increment();
        ep.queries.add(s.stats().queries());
        ep.sqlNanos.add(s.stats().sqlNanos());
        ep.httpNanos.add(s.durationNanos());
        ep.maxQueriesPerRequest.accumulateAndGet(s.stats().queries(), Math::max);
        if (!s.nPlusOne().isEmpty()) {
            ep.nPlusOneRequests.increment();
        }
        for (Map.Entry<String, RequestStats.Entry> e : s.stats().bySql().entrySet()) {
            RequestStats.Entry v = e.getValue();
            recordSql(ep.name, e.getKey(), v.caller(), v.count(), v.nanos(), v.maxNanos());
        }
        for (String normalized : s.nPlusOne()) {
            RequestStats.Entry v = s.stats().bySql().get(normalized);
            NPlusOneStats n = bounded(nPlusOne, new SqlKey(ep.name, normalized), MAX_NPLUSONE, NPlusOneStats::new);
            if (n != null) {
                n.record(v.count(), v.nanos(), v.caller());
            }
        }
    }

    /** @return true the first time this (endpoint, sql) N+1 is seen (used to log only once). */
    public boolean isNewNPlusOne(String endpoint, String normalizedSql) {
        NPlusOneStats n = nPlusOne.get(new SqlKey(endpointName(endpoint), normalizedSql));
        return n == null || n.occurrences.sum() == 1;
    }

    private void recordSql(String endpoint, String normalizedSql, String caller, int calls, long nanos, long maxNanos) {
        SqlStats st = bounded(sql, new SqlKey(endpoint, normalizedSql), MAX_SQL, SqlStats::new);
        if (st != null) {
            st.record(calls, nanos, maxNanos, caller);
        }
    }

    private EndpointStats endpoint(String name) {
        String key = endpointName(name);
        return endpoints.computeIfAbsent(key, EndpointStats::new);
    }

    private String endpointName(String name) {
        if (endpoints.containsKey(name) || endpoints.size() < MAX_ENDPOINTS) {
            return name;
        }
        dropped.increment();
        return OTHER_ENDPOINT;
    }

    private <V> V bounded(Map<SqlKey, V> map, SqlKey key, int max, java.util.function.Function<SqlKey, V> factory) {
        V v = map.get(key);
        if (v != null) {
            return v;
        }
        if (map.size() >= max) {
            dropped.increment();
            return null;
        }
        return map.computeIfAbsent(key, factory);
    }

    // ---------------------------------------------------------------- views

    public List<Map<String, Object>> top(int limit) {
        List<Map.Entry<SqlKey, SqlStats>> entries = new ArrayList<>(sql.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<SqlKey, SqlStats> e) -> e.getValue().nanos.sum()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<SqlKey, SqlStats> e : entries.subList(0, Math.min(limit, entries.size()))) {
            SqlStats s = e.getValue();
            long calls = s.calls.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", e.getKey().endpoint());
            m.put("sql", e.getKey().sql());
            m.put("caller", s.lastCaller);
            m.put("callers", s.callers());
            m.put("calls", calls);
            m.put("totalTimeMs", millis(s.nanos.sum()));
            m.put("avgTimeMs", calls == 0 ? 0.0 : millis(s.nanos.sum() / calls));
            m.put("maxTimeMs", millis(s.maxNanos.get()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> nPlusOne() {
        List<Map.Entry<SqlKey, NPlusOneStats>> entries = new ArrayList<>(nPlusOne.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<SqlKey, NPlusOneStats> e) -> e.getValue().totalQueries.sum()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<SqlKey, NPlusOneStats> e : entries) {
            NPlusOneStats n = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", e.getKey().endpoint());
            m.put("sql", e.getKey().sql());
            m.put("caller", n.caller);
            m.put("occurrences", n.occurrences.sum());
            m.put("maxQueriesPerRequest", n.maxCount.get());
            m.put("lastQueriesPerRequest", n.lastCount);
            m.put("totalQueries", n.totalQueries.sum());
            m.put("totalTimeMs", millis(n.nanos.sum()));
            m.put("lastSeen", Instant.ofEpochMilli(n.lastSeenMillis).toString());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> endpoints() {
        List<Map<String, Object>> out = new ArrayList<>();
        endpoints.values().stream()
                .sorted(Comparator.comparingLong((EndpointStats e) -> e.sqlNanos.sum()).reversed())
                .forEach(e -> {
                    long req = e.requests.sum();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("endpoint", e.name);
                    m.put("requests", req);
                    m.put("queries", e.queries.sum());
                    m.put("queriesPerRequest", req == 0 ? null : (double) e.queries.sum() / req);
                    m.put("maxQueriesPerRequest", e.maxQueriesPerRequest.get());
                    m.put("sqlTimeMs", millis(e.sqlNanos.sum()));
                    m.put("httpTimeMs", millis(e.httpNanos.sum()));
                    m.put("nPlusOneRequests", e.nPlusOneRequests.sum());
                    out.add(m);
                });
        return out;
    }

    Map<String, EndpointStats> endpointStats() {
        return endpoints;
    }

    static double millis(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    // ---------------------------------------------------------------- state

    record SqlKey(String endpoint, String sql) { }

    static final class EndpointStats {
        final String name;
        final LongAdder requests = new LongAdder();
        final LongAdder queries = new LongAdder();
        final LongAdder sqlNanos = new LongAdder();
        final LongAdder httpNanos = new LongAdder();
        final LongAdder nPlusOneRequests = new LongAdder();
        final AtomicLong maxQueriesPerRequest = new AtomicLong();

        EndpointStats(String name) { this.name = name; }
    }

    static final class SqlStats {
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        private final Set<String> callers = new LinkedHashSet<>();
        volatile String lastCaller;

        SqlStats(SqlKey k) { }

        void record(int n, long ns, long max, String caller) {
            calls.add(n);
            nanos.add(ns);
            maxNanos.accumulateAndGet(max, Math::max);
            lastCaller = caller;
            if (caller != null) {
                synchronized (callers) {
                    if (callers.size() < MAX_CALLERS_PER_SQL) callers.add(caller);
                }
            }
        }

        List<String> callers() {
            synchronized (callers) {
                return List.copyOf(callers);
            }
        }
    }

    static final class NPlusOneStats {
        final LongAdder occurrences = new LongAdder();
        final LongAdder totalQueries = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final AtomicLong maxCount = new AtomicLong();
        volatile int lastCount;
        volatile String caller;
        volatile long lastSeenMillis;

        NPlusOneStats(SqlKey k) { }

        void record(int count, long ns, String c) {
            occurrences.increment();
            totalQueries.add(count);
            nanos.add(ns);
            maxCount.accumulateAndGet(count, Math::max);
            lastCount = count;
            caller = c;
            lastSeenMillis = System.currentTimeMillis();
        }
    }
}
