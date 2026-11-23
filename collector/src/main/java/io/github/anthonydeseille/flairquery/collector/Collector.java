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

    final AtomicLong cycles = new AtomicLong();
    final AtomicLong errors = new AtomicLong();
    final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    final AtomicLong logLag = new AtomicLong();

    public Collector(PostgresSource source, PlanSource planSource, Store store, Duration retention, Clock clock) {
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

    /** Never throws: a failed cycle is logged and counted, the next one retries. */
    public void runSafely() {
        try {
            collectOnce();
        } catch (Exception e) {
            errors.incrementAndGet();
            Log.warn("collection cycle failed", e, "target", target());
        }
    }

    public Store.Cycle collectOnce() throws Exception {
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
                attribution = Attribution.of(batch.entries(), source.target().user());
                cursors = batch.cursors();
                logEntries = batch.entries().size();
                unparsable = batch.unparsable();
                logLag.set(batch.lagBytes());
            } catch (Exception e) {
                // statistics are still stored: plans are an enrichment
                Log.warn("auto_explain log not readable this cycle (statistics collected anyway)", e, "target", target());
            }
        }

        double durationMs = (System.nanoTime() - started) / 1e6;
        Store.Cycle cycle = new Store.Cycle(target(), now, state.last().map(Store.SnapshotInfo::takenAt).orElse(null),
                baseline, reading.info().statsReset(), resetSeen, reading.info().dealloc(), deallocDelta,
                reading.statements().size(), durationMs, statements, tables, attribution, cursors, logEntries,
                logLag.get(), unparsable);
        store.save(cycle);
        int purged = store.deleteSnapshotsBefore(target(), now.minus(retention));

        cycles.incrementAndGet();
        lastSuccess.set(now);
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
}
