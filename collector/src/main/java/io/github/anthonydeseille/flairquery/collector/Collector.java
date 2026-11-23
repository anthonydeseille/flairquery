package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.delta.DeltaCalculator;
import io.github.anthonydeseille.flairquery.collector.delta.DeltaCalculator.Delta;
import io.github.anthonydeseille.flairquery.collector.log.Log;
import io.github.anthonydeseille.flairquery.collector.plan.Attribution;
import io.github.anthonydeseille.flairquery.collector.source.PlanSource;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;
import io.github.anthonydeseille.flairquery.collector.source.StatementRow;
import io.github.anthonydeseille.flairquery.collector.source.TableRow;
import io.github.anthonydeseille.flairquery.collector.store.Store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One collection cycle: read the target (read-only), compute deltas, store them in fq-store. */
public final class Collector {

    private final PostgresSource source;
    private final PlanSource planSource;   // null: statistics only
    private final Store store;
    private final Duration retention;
    private final Clock clock;
    private final Duration columnStatsInterval;   // zero: pg_stats not read
    private final io.github.anthonydeseille.flairquery.collector.source.AgentSource agentSource;   // null: none
    private Instant lastColumnStats;

    final AtomicLong columnStats = new AtomicLong();
    final AtomicLong lastCycleMicros = new AtomicLong();

    final AtomicLong cycles = new AtomicLong();
    final AtomicLong errors = new AtomicLong();
    final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    final AtomicLong logLag = new AtomicLong();

    public Collector(PostgresSource source, PlanSource planSource, Store store, Duration retention,
                     Duration columnStatsInterval, Clock clock) {
        this(source, planSource, null, store, retention, columnStatsInterval, clock);
    }

    public Collector(PostgresSource source, PlanSource planSource,
                     io.github.anthonydeseille.flairquery.collector.source.AgentSource agentSource, Store store,
                     Duration retention, Duration columnStatsInterval, Clock clock) {
        this.agentSource = agentSource;
        this.columnStatsInterval = columnStatsInterval;
        this.source = source;
        this.planSource = planSource;
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    public String target() {
        return source.target().name();
    }

    public long cycles() { return cycles.get(); }
    public long errors() { return errors.get(); }
    public Instant lastSuccess() { return lastSuccess.get(); }
    public long logLag() { return logLag.get(); }
    public long columnStats() { return columnStats.get(); }
    /** -1: no agent configured, 0: not read on the last cycle, 1: read. */
    public int agentUp() { return agentSource == null ? -1 : agentUp; }
    private volatile int agentUp;
    public double lastCycleSeconds() { return lastCycleMicros.get() / 1e6; }

    /** Never throws: a failed cycle is logged and counted, the next one retries. */
    public void runSafely() {
        try {
            collectOnce();
        } catch (Exception e) {
            errors.incrementAndGet();
            Log.warn("collection cycle failed", e, "target", target());
        }
    }

    /** Cycles are serialized: the scheduler and the marks endpoint both run them. */
    public synchronized Store.Cycle collectOnce() throws Exception {
        long started = System.nanoTime();
        Instant now = clock.instant();
        PostgresSource.Reading reading = source.read();
        Store.State state = store.loadState(target());

        boolean baseline = state.last().isEmpty();
        Instant previousReset = state.last().map(Store.SnapshotInfo::statsReset).orElse(null);
        boolean resetSeen = !baseline && !Objects.equals(previousReset, reading.info().statsReset());
        Long previousDealloc = state.last().map(Store.SnapshotInfo::deallocTotal).orElse(null);
        long deallocDelta = previousDealloc == null ? 0
                : Math.max(0, reading.info().dealloc() - (resetSeen ? 0 : previousDealloc));

        List<Store.StatementChange> statements = new ArrayList<>();
        int active = 0;
        for (StatementRow row : reading.statements()) {
            Store.Cursor cursor = state.statements().get(row.key());
            Delta delta = baseline ? null
                    : DeltaCalculator.compute(cursor == null ? null : cursor.counters(), row.counters(), resetSeen);
            boolean cursorChanged = cursor == null || !cursor.counters().equals(row.counters());
            if (delta != null || cursorChanged) {
                statements.add(new Store.StatementChange(row, delta, cursorChanged));
            }
            if (delta != null) active++;
        }
        List<Store.TableChange> tables = new ArrayList<>();
        for (TableRow row : reading.tables()) {
            Store.Cursor cursor = state.tables().get(new Store.TableKey(row.dbName(), row.relid()));
            Delta delta = baseline ? null
                    : DeltaCalculator.compute(cursor == null ? null : cursor.counters(), row.counters(), false);
            boolean cursorChanged = cursor == null || !cursor.counters().equals(row.counters());
            if (delta != null || cursorChanged) {
                tables.add(new Store.TableChange(row, delta, cursorChanged));
            }
        }

        // auto_explain: exact per-execution attribution (query_id + the execution's own fq: tag)
        Attribution.Result attribution = new Attribution.Result(java.util.Map.of(), java.util.Map.of());
        java.util.Map<String, io.github.anthonydeseille.flairquery.collector.source.LogCursor> cursors = state.logCursors();
        int logEntries = 0;
        int unparsable = 0;
        if (planSource != null) {
            try {
                PlanSource.Batch batch = planSource.read(state.logCursors(), baseline);
                attribution = Attribution.of(batch.entries(), source.target().user(),
                        new io.github.anthonydeseille.flairquery.collector.plan.QueryIdResolver(reading.statements()));
                cursors = batch.cursors();
                logEntries = batch.entries().size();
                unparsable = batch.unparsable();
                logLag.set(batch.lagBytes());
            } catch (Exception e) {
                // statistics are still stored: plans are an enrichment
                Log.warn("auto_explain log not readable this cycle (statistics collected anyway)", e, "target", target());
            }
        }

        // agent: HTTP requests per endpoint (an enrichment: never fails the cycle)
        List<Store.EndpointChange> endpoints = new ArrayList<>();
        Boolean agentRead = null;
        if (agentSource != null) {
            try {
                boolean agentBaseline = baseline || state.endpoints().isEmpty();   // first contact: cursors only
                for (var row : agentSource.read()) {
                    Store.Cursor cursor = state.endpoints().get(new Store.EndpointKey(row.app(), row.endpoint()));
                    Delta delta = agentBaseline ? null
                            : DeltaCalculator.compute(cursor == null ? null : cursor.counters(), row.counters(), false);
                    boolean cursorChanged = cursor == null || !cursor.counters().equals(row.counters());
                    if (delta != null || cursorChanged) {
                        endpoints.add(new Store.EndpointChange(row, delta, cursorChanged));
                    }
                }
                agentRead = true;
                agentUp = 1;
            } catch (Exception e) {
                agentRead = false;
                agentUp = 0;
                Log.warn("agent endpoints not readable this cycle (per-request figures unavailable)", e,
                        "target", target(), "agent", agentSource.uri().toString());
            }
        }

        double durationMs = (System.nanoTime() - started) / 1e6;
        Store.Cycle cycle = new Store.Cycle(target(), now, state.last().map(Store.SnapshotInfo::takenAt).orElse(null),
                baseline, reading.info().statsReset(), resetSeen, reading.info().dealloc(), deallocDelta,
                reading.statements().size(), durationMs, statements, tables, attribution, cursors, logEntries,
                logLag.get(), unparsable, endpoints, agentRead);
        store.save(cycle);
        int purged = store.deleteSnapshotsBefore(target(), now.minus(retention));

        maybeRefreshColumnStats(now);

        cycles.incrementAndGet();
        lastSuccess.set(now);
        lastCycleMicros.set((System.nanoTime() - started) / 1000);
        Log.info("snapshot", "target", target(), "baseline", baseline, "statements", reading.statements().size(),
                "activeStatements", active, "statsReset", resetSeen, "evicted", deallocDelta,
                "durationMs", Math.round(durationMs), "purgedSnapshots", purged, "planEntries", logEntries,
                "logLagBytes", logLag.get());
        if (deallocDelta > 0) {
            Log.warn("pg_stat_statements evicted entries: deltas of evicted statements are lost; "
                    + "consider raising pg_stat_statements.max", null, "target", target(), "evicted", deallocDelta);
        }
        return cycle;
    }

    /**
     * Every {@code columnStatsInterval}, re-reads all the tables seen; in between, reads only the tables seen
     * for the first time (or without statistics yet, e.g. not analyzed), so a new table never waits for the
     * interval.
     */
    private void maybeRefreshColumnStats(Instant now) {
        if (columnStatsInterval.isZero()) return;
        boolean due = lastColumnStats == null || !now.isBefore(lastColumnStats.plus(columnStatsInterval));
        try {
            if (due) {
                if (refreshColumnStats(now) > 0) {
                    lastColumnStats = now;   // otherwise retried next cycle (no plan seen yet)
                }
            } else {
                refreshNewTables(now);
            }
        } catch (Exception e) {
            // an enrichment: never fails the cycle
            Log.warn("pg_stats not readable this cycle", e, "target", target());
        }
    }

    /**
     * Reads value-free pg_stats columns of the tables seen in plans or sequential scans during the retention
     * window. Returns the number of columns stored.
     */
    public synchronized int refreshColumnStats(Instant now) throws Exception {
        return readColumnStats(now, store.relationsSeen(target(), now.minus(retention)));
    }

    /** Only the tables seen that have no statistics stored yet. Returns the number of columns stored. */
    public synchronized int refreshNewTables(Instant now) throws Exception {
        java.util.Set<String> known = store.columnStatsTables(target());
        List<String> missing = store.relationsSeen(target(), now.minus(retention)).stream()
                .filter(t -> !known.contains(t)).toList();
        return readColumnStats(now, missing);
    }

    private int readColumnStats(Instant now, List<String> tables) throws Exception {
        if (tables.isEmpty()) return 0;
        List<io.github.anthonydeseille.flairquery.collector.source.ColumnStatsRow> rows = source.readColumnStats(tables);
        store.upsertColumnStats(target(), now, rows);
        columnStats.set(store.columnStatsCount(target()));
        Log.debug("column statistics", "target", target(), "tables", tables.size(), "columns", rows.size());
        return rows.size();
    }

    /**
     * Sets a named mark aligned on a collection cycle run now: activity up to the mark is in the snapshots
     * taken at or before it, activity after it in later snapshots. Empty if the name is already used.
     */
    public synchronized java.util.Optional<Store.Mark> mark(String name, String note) throws Exception {
        Store.Cycle cycle = collectOnce();
        Long snapshotId = store.snapshotIdAt(target(), cycle.takenAt());
        Store.Mark mark = new Store.Mark(name, note, cycle.takenAt(), snapshotId);
        return store.insertMark(target(), mark) ? java.util.Optional.of(mark) : java.util.Optional.empty();
    }
}
