package io.github.anthonydeseille.flairquery.agent.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per HTTP request counters (single thread: the one serving the request). */
public final class RequestStats {

    static final int MAX_DISTINCT_SQL = 1_000;

    private final Map<String, Entry> bySql = new LinkedHashMap<>();
    private int queries;
    private long sqlNanos;
    private int errors;
    private int droppedSql;

    public static final class Entry {
        int count;
        long nanos;
        long maxNanos;
        String caller;

        public int count() { return count; }
        public long nanos() { return nanos; }
        public long maxNanos() { return maxNanos; }
        public String caller() { return caller; }
    }

    public void record(String normalizedSql, String caller, long nanos, boolean failed) {
        queries++;
        sqlNanos += nanos;
        if (failed) {
            errors++;
        }
        Entry e = bySql.get(normalizedSql);
        if (e == null) {
            if (bySql.size() >= MAX_DISTINCT_SQL) {
                droppedSql++;
                return;
            }
            e = new Entry();
            bySql.put(normalizedSql, e);
        }
        e.count++;
        e.nanos += nanos;
        e.maxNanos = Math.max(e.maxNanos, nanos);
        e.caller = caller;
    }

    // ---------------------------------------------------------------- JPA profile (phase 3)

    static final int MAX_DISTINCT_ROLES = 200;

    private final Map<String, int[]> entities = new LinkedHashMap<>();
    private final Map<String, LazyLoad> lazyLoads = new LinkedHashMap<>();
    private int inMemoryPaginations;
    private boolean openInView;

    /** Lazy loads of one collection role or proxied entity. */
    public static final class LazyLoad {
        final String kind;
        int count;
        int outsideTransaction;
        long nanos;
        String caller;

        LazyLoad(String kind) { this.kind = kind; }

        public String kind() { return kind; }
        public int count() { return count; }
        public int outsideTransaction() { return outsideTransaction; }
        public long nanos() { return nanos; }
        public String caller() { return caller; }
    }

    public void entityLoaded(String type) {
        int[] n = entities.get(type);
        if (n == null) {
            if (entities.size() >= MAX_DISTINCT_ROLES) return;
            entities.put(type, n = new int[1]);
        }
        n[0]++;
    }

    public void lazyLoaded(String kind, String role, long nanos, boolean outsideTransaction, String caller) {
        LazyLoad l = lazyLoads.get(role);
        if (l == null) {
            if (lazyLoads.size() >= MAX_DISTINCT_ROLES) return;
            lazyLoads.put(role, l = new LazyLoad(kind));
        }
        l.count++;
        l.nanos += nanos;
        if (outsideTransaction) l.outsideTransaction++;
        l.caller = caller;
    }

    public void inMemoryPagination() { inMemoryPaginations++; }

    public void openInView(boolean active) { openInView = active; }

    /** Entities put into the persistence context, by class name. */
    public Map<String, int[]> entities() { return entities; }
    public Map<String, LazyLoad> lazyLoads() { return lazyLoads; }
    public int inMemoryPaginations() { return inMemoryPaginations; }
    public boolean openInView() { return openInView; }

    public Map<String, Entry> bySql() { return bySql; }
    public int queries() { return queries; }
    public long sqlNanos() { return sqlNanos; }
    public int errors() { return errors; }
    public int droppedSql() { return droppedSql; }
}
