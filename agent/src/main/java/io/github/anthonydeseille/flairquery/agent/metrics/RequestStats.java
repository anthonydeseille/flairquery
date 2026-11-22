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

    public Map<String, Entry> bySql() { return bySql; }
    public int queries() { return queries; }
    public long sqlNanos() { return sqlNanos; }
    public int errors() { return errors; }
    public int droppedSql() { return droppedSql; }
}
