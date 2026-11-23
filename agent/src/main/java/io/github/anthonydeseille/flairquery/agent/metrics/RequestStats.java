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
    private long rows;

    public static final class Entry {
        int count;
        long nanos;
        long maxNanos;
        String caller;
        long rows;
        /** Collection role / entity whose lazy loading triggered this statement (last one seen), or null. */
        String lazyRole;

        public int count() { return count; }
        public long nanos() { return nanos; }
        public long maxNanos() { return maxNanos; }
        public String caller() { return caller; }
        public long rows() { return rows; }
        public String lazyRole() { return lazyRole; }
    }

    public void record(String normalizedSql, String caller, long nanos, boolean failed) {
        record(normalizedSql, caller, nanos, failed, null);
    }

    /** @return the statement entry, or null when the per-request distinct SQL bound is reached */
    public Entry record(String normalizedSql, String caller, long nanos, boolean failed, String lazyRole) {
        queries++;
        sqlNanos += nanos;
        if (failed) {
            errors++;
        }
        Entry e = bySql.get(normalizedSql);
        if (e == null) {
            if (bySql.size() >= MAX_DISTINCT_SQL) {
                droppedSql++;
                return null;
            }
            e = new Entry();
            bySql.put(normalizedSql, e);
        }
        e.count++;
        e.nanos += nanos;
        e.maxNanos = Math.max(e.maxNanos, nanos);
        e.caller = caller;
        if (lazyRole != null) {
            e.lazyRole = lazyRole;
        }
        return e;
    }

    /** One row read from a result set of {@code entry} (null: statement not tracked). */
    public void rowRead(Entry entry) {
        rows++;
        if (entry != null) {
            entry.rows++;
        }
    }

    public long rows() { return rows; }

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

    private final Map<String, java.util.BitSet> attributesRead = new LinkedHashMap<>();

    /** Attribute {@code index} of the entity class {@code entity} was read in this request. */
    public void attributeRead(String entity, int index) {
        java.util.BitSet read = attributesRead.get(entity);
        if (read == null) {
            if (attributesRead.size() >= MAX_DISTINCT_ROLES) return;
            attributesRead.put(entity, read = new java.util.BitSet());
        }
        read.set(index);
    }

    /** Persistent attributes read, per entity class. */
    public Map<String, java.util.BitSet> attributesRead() { return attributesRead; }

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
