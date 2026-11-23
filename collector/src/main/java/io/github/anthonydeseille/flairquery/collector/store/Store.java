package io.github.anthonydeseille.flairquery.collector.store;

import io.github.anthonydeseille.flairquery.collector.delta.Counters;
import io.github.anthonydeseille.flairquery.collector.delta.DeltaCalculator.Delta;
import io.github.anthonydeseille.flairquery.collector.plan.Attribution;
import io.github.anthonydeseille.flairquery.collector.source.LogCursor;
import io.github.anthonydeseille.flairquery.collector.source.StatementKey;
import io.github.anthonydeseille.flairquery.collector.source.StatementRow;
import io.github.anthonydeseille.flairquery.collector.source.TableRow;
import io.github.anthonydeseille.flairquery.sql.SqlTag;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The collector's own database (fq-store). Every cycle is written in a single transaction. */
public final class Store {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final String url;
    private final String user;
    private final String password;

    public Store(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Creates / upgrades the schema (Liquibase, changelog db/store/changelog.yaml). */
    public void migrate() throws Exception {
        Logger.getLogger("liquibase").setLevel(Level.WARNING);
        try (Connection c = connect()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new CommandScope(UpdateCommandStep.COMMAND_NAME)
                    .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db)
                    .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/store/changelog.yaml")
                    .execute();
        }
    }

    // ---------------------------------------------------------------- state needed to compute deltas

    public record SnapshotInfo(long id, Instant takenAt, Instant statsReset, Long deallocTotal) { }

    public record Cursor(long id, Counters counters) { }

    public record TableKey(String dbName, long relid) { }

    public record State(Optional<SnapshotInfo> last, Map<StatementKey, Cursor> statements, Map<TableKey, Cursor> tables,
                        Map<String, LogCursor> logCursors) { }

    public State loadState(String target) throws SQLException {
        try (Connection c = connect()) {
            Optional<SnapshotInfo> last = Optional.empty();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, taken_at, stats_reset, dealloc_total FROM fq_snapshot WHERE target = ? ORDER BY taken_at DESC LIMIT 1")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Timestamp reset = rs.getTimestamp(3);
                        long dealloc = rs.getLong(4);
                        last = Optional.of(new SnapshotInfo(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                                reset == null ? null : reset.toInstant(), rs.wasNull() ? null : dealloc));
                    }
                }
            }
            Map<StatementKey, Cursor> statements = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT s.id, s.dbid, s.userid, s.queryid, s.toplevel, c.calls, c.total_exec_time, c.rows,
                           c.shared_blks_hit, c.shared_blks_read, c.temp_blks_read, c.temp_blks_written
                    FROM fq_statement_cursor c JOIN fq_statement s ON s.id = c.statement_id WHERE s.target = ?""")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        statements.put(new StatementKey(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)),
                                new Cursor(rs.getLong(1), Counters.of(rs.getLong(6), rs.getDouble(7), rs.getLong(8),
                                        rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12))));
                    }
                }
            }
            Map<TableKey, Cursor> tables = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.db_name, t.relid, c.seq_scan, c.seq_tup_read, c.idx_scan, c.idx_tup_fetch,
                           c.n_tup_ins, c.n_tup_upd, c.n_tup_del
                    FROM fq_table_cursor c JOIN fq_table t ON t.id = c.table_id WHERE t.target = ?""")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.put(new TableKey(rs.getString(2), rs.getLong(3)),
                                new Cursor(rs.getLong(1), Counters.of(rs.getLong(4), rs.getLong(5), rs.getLong(6),
                                        rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10))));
                    }
                }
            }
            Map<String, LogCursor> logCursors = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT file_name, read_offset, head FROM fq_log_cursor WHERE target = ?")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        logCursors.put(rs.getString(1), new LogCursor(rs.getString(1), rs.getLong(2), rs.getString(3)));
                    }
                }
            }
            return new State(last, statements, tables, logCursors);
        }
    }

    // ---------------------------------------------------------------- writing one cycle

    public record StatementChange(StatementRow row, Delta delta, boolean cursorChanged) { }

    public record TableChange(TableRow row, Delta delta, boolean cursorChanged) { }

    public record Cycle(String target, Instant takenAt, Instant previousAt, boolean baseline, Instant statsReset,
                        boolean statsResetSeen, long deallocTotal, long deallocDelta, int statementCount,
                        double durationMs, List<StatementChange> statements, List<TableChange> tables,
                        Attribution.Result attribution, Map<String, LogCursor> logCursors, int logEntries,
                        long logLagBytes, int logUnparsable) { }

    public long save(Cycle cycle) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                long snapshotId = insertSnapshot(c, cycle);
                for (StatementChange ch : cycle.statements()) {
                    long statementId = upsertStatement(c, cycle.target(), cycle.takenAt(), ch.row());
                    if (ch.delta() != null && !cycle.baseline()) {
                        insertStatementDelta(c, snapshotId, statementId, ch);
                    }
                    if (ch.cursorChanged()) {
                        upsertStatementCursor(c, statementId, ch.row().counters());
                    }
                }
                for (TableChange ch : cycle.tables()) {
                    long tableId = upsertTable(c, cycle.target(), ch.row());
                    if (ch.delta() != null && !cycle.baseline()) {
                        insertTableDelta(c, snapshotId, tableId, ch);
                    }
                    if (ch.cursorChanged()) {
                        upsertTableCursor(c, tableId, ch.row().counters());
                    }
                }
                if (!cycle.baseline()) {
                    insertAttributions(c, snapshotId, cycle.attribution());
                }
                upsertPlans(c, cycle.target(), cycle.takenAt(), cycle.attribution());
                upsertLogCursors(c, cycle.target(), cycle.takenAt(), cycle.logCursors());
                c.commit();
                return snapshotId;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static long insertSnapshot(Connection c, Cycle cy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_snapshot (target, taken_at, previous_at, baseline, stats_reset, stats_reset_seen,
                                         dealloc_total, dealloc_delta, statements, duration_ms,
                                         log_entries, log_lag_bytes, log_unparsable)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
            ps.setString(1, cy.target());
            ps.setTimestamp(2, Timestamp.from(cy.takenAt()));
            setInstant(ps, 3, cy.previousAt());
            ps.setBoolean(4, cy.baseline());
            setInstant(ps, 5, cy.statsReset());
            ps.setBoolean(6, cy.statsResetSeen());
            ps.setLong(7, cy.deallocTotal());
            ps.setLong(8, cy.deallocDelta());
            ps.setInt(9, cy.statementCount());
            ps.setDouble(10, cy.durationMs());
            ps.setInt(11, cy.logEntries());
            ps.setLong(12, cy.logLagBytes());
            ps.setInt(13, cy.logUnparsable());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long upsertStatement(Connection c, String target, Instant now, StatementRow r) throws SQLException {
        Optional<SqlTag.FqTag> tag = SqlTag.parse(r.query());
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_statement (target, dbid, userid, queryid, toplevel, db_name, role_name, query,
                                          app, endpoint, caller, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (target, dbid, userid, queryid, toplevel) DO UPDATE
                   SET last_seen = excluded.last_seen,
                       query = coalesce(fq_statement.query, excluded.query),
                       app = coalesce(fq_statement.app, excluded.app),
                       endpoint = coalesce(fq_statement.endpoint, excluded.endpoint),
                       caller = coalesce(fq_statement.caller, excluded.caller)
                RETURNING id""")) {
            ps.setString(1, target);
            ps.setLong(2, r.key().dbid());
            ps.setLong(3, r.key().userid());
            ps.setLong(4, r.key().queryid());
            ps.setBoolean(5, r.key().toplevel());
            ps.setString(6, r.dbName());
            ps.setString(7, r.roleName());
            ps.setString(8, r.query());
            ps.setString(9, tag.map(SqlTag.FqTag::app).orElse(null));
            ps.setString(10, tag.map(SqlTag.FqTag::endpoint).orElse(null));
            ps.setString(11, tag.map(SqlTag.FqTag::caller).orElse(null));
            ps.setTimestamp(12, Timestamp.from(now));
            ps.setTimestamp(13, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertStatementDelta(Connection c, long snapshotId, long statementId, StatementChange ch)
            throws SQLException {
        Counters d = ch.delta().values();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_statement_delta (snapshot_id, statement_id, calls, total_exec_time, max_exec_time, rows,
                    shared_blks_hit, shared_blks_read, temp_blks_read, temp_blks_written, restarted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, snapshotId);
            ps.setLong(2, statementId);
            ps.setLong(3, (long) d.get(0));
            ps.setDouble(4, d.get(1));
            ps.setDouble(5, ch.row().maxExecTime());
            ps.setLong(6, (long) d.get(2));
            ps.setLong(7, (long) d.get(3));
            ps.setLong(8, (long) d.get(4));
            ps.setLong(9, (long) d.get(5));
            ps.setLong(10, (long) d.get(6));
            ps.setBoolean(11, ch.delta().restarted());
            ps.executeUpdate();
        }
    }

    private static void upsertStatementCursor(Connection c, long statementId, Counters v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_statement_cursor (statement_id, calls, total_exec_time, rows, shared_blks_hit,
                    shared_blks_read, temp_blks_read, temp_blks_written) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (statement_id) DO UPDATE SET calls = excluded.calls, total_exec_time = excluded.total_exec_time,
                    rows = excluded.rows, shared_blks_hit = excluded.shared_blks_hit, shared_blks_read = excluded.shared_blks_read,
                    temp_blks_read = excluded.temp_blks_read, temp_blks_written = excluded.temp_blks_written""")) {
            ps.setLong(1, statementId);
            ps.setLong(2, (long) v.get(0));
            ps.setDouble(3, v.get(1));
            for (int i = 2; i < 7; i++) {
                ps.setLong(i + 2, (long) v.get(i));
            }
            ps.executeUpdate();
        }
    }

    private static long upsertTable(Connection c, String target, TableRow r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_table (target, db_name, relid, schema_name, table_name) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (target, db_name, relid) DO UPDATE SET schema_name = excluded.schema_name,
                    table_name = excluded.table_name RETURNING id""")) {
            ps.setString(1, target);
            ps.setString(2, r.dbName());
            ps.setLong(3, r.relid());
            ps.setString(4, r.schema());
            ps.setString(5, r.table());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertTableDelta(Connection c, long snapshotId, long tableId, TableChange ch) throws SQLException {
        Counters d = ch.delta().values();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_table_delta (snapshot_id, table_id, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch,
                    n_tup_ins, n_tup_upd, n_tup_del, n_live_tup, restarted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, snapshotId);
            ps.setLong(2, tableId);
            for (int i = 0; i < 7; i++) {
                ps.setLong(i + 3, (long) d.get(i));
            }
            ps.setLong(10, ch.row().liveTuples());
            ps.setBoolean(11, ch.delta().restarted());
            ps.executeUpdate();
        }
    }

    private static void upsertTableCursor(Connection c, long tableId, Counters v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_table_cursor (table_id, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch, n_tup_ins,
                    n_tup_upd, n_tup_del) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (table_id) DO UPDATE SET seq_scan = excluded.seq_scan, seq_tup_read = excluded.seq_tup_read,
                    idx_scan = excluded.idx_scan, idx_tup_fetch = excluded.idx_tup_fetch, n_tup_ins = excluded.n_tup_ins,
                    n_tup_upd = excluded.n_tup_upd, n_tup_del = excluded.n_tup_del""")) {
            ps.setLong(1, tableId);
            for (int i = 0; i < 7; i++) {
                ps.setLong(i + 2, (long) v.get(i));
            }
            ps.executeUpdate();
        }
    }

    private static void insertAttributions(Connection c, long snapshotId, Attribution.Result r) throws SQLException {
        if (r == null || r.executions().isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_attribution (snapshot_id, db_name, role_name, queryid, app, endpoint, caller,
                                            executions, total_ms, max_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (Map.Entry<Attribution.Key, Attribution.Executions> e : r.executions().entrySet()) {
                Attribution.Key k = e.getKey();
                ps.setLong(1, snapshotId);
                ps.setString(2, k.dbName());
                ps.setString(3, k.roleName());
                ps.setLong(4, k.queryId());
                ps.setString(5, k.app());
                ps.setString(6, k.endpoint());
                ps.setString(7, k.caller());
                ps.setLong(8, e.getValue().count);
                ps.setDouble(9, e.getValue().totalMs);
                ps.setDouble(10, e.getValue().maxMs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void upsertPlans(Connection c, String target, Instant now, Attribution.Result r) throws SQLException {
        if (r == null || r.plans().isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_plan (target, db_name, queryid, fingerprint, summary, plan, executions, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (target, db_name, queryid, fingerprint) DO UPDATE
                   SET executions = fq_plan.executions + excluded.executions, last_seen = excluded.last_seen""")) {
            for (Map.Entry<Attribution.PlanKey, Attribution.PlanObservation> e : r.plans().entrySet()) {
                ps.setString(1, target);
                ps.setString(2, e.getKey().dbName());
                ps.setLong(3, e.getKey().queryId());
                ps.setString(4, e.getKey().fingerprint());
                ps.setString(5, e.getValue().summary.toString());
                ps.setString(6, e.getValue().plan.toString());
                ps.setLong(7, e.getValue().count);
                ps.setTimestamp(8, Timestamp.from(now));
                ps.setTimestamp(9, Timestamp.from(now));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void upsertLogCursors(Connection c, String target, Instant now, Map<String, LogCursor> cursors)
            throws SQLException {
        if (cursors == null || cursors.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fq_log_cursor (target, file_name, read_offset, head, updated_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (target, file_name) DO UPDATE
                   SET read_offset = excluded.read_offset, head = excluded.head, updated_at = excluded.updated_at""")) {
            for (LogCursor lc : cursors.values()) {
                ps.setString(1, target);
                ps.setString(2, lc.fileName());
                ps.setLong(3, lc.offset());
                ps.setString(4, lc.head());
                ps.setTimestamp(5, Timestamp.from(now));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Retention: snapshots (and their deltas, by cascade) older than the cutoff. */
    public int deleteSnapshotsBefore(String target, Instant cutoff) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM fq_snapshot WHERE target = ? AND taken_at < ?")) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            return ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- read side (HTTP API)

    public enum Order {
        TIME("total_exec_time"), CALLS("calls"), TEMP("temp_blks_written"), READS("shared_blks_read"), MEAN("mean_exec_time");

        final String column;

        Order(String column) {
            this.column = column;
        }
    }

    public List<Map<String, Object>> topStatements(String target, Instant since, int limit, Order order) throws SQLException {
        String sql = """
                SELECT s.id, s.queryid, s.app, s.endpoint, s.caller, s.role_name, s.db_name, s.query,
                       sum(d.calls) AS calls, sum(d.total_exec_time) AS total_exec_time,
                       sum(d.total_exec_time) / nullif(sum(d.calls), 0) AS mean_exec_time,
                       max(d.max_exec_time) AS max_exec_time, sum(d.rows) AS rows,
                       sum(d.shared_blks_hit) AS shared_blks_hit, sum(d.shared_blks_read) AS shared_blks_read,
                       sum(d.temp_blks_written) AS temp_blks_written, bool_or(d.restarted) AS restarted
                FROM fq_statement_delta d
                JOIN fq_snapshot sn ON sn.id = d.snapshot_id
                JOIN fq_statement s ON s.id = d.statement_id
                WHERE sn.target = ? AND sn.taken_at > ?
                GROUP BY s.id
                ORDER BY %s DESC NULLS LAST
                LIMIT ?""".formatted(order.column);
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(since));
            ps.setInt(3, limit);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("queryid", Long.toString(rs.getLong("queryid")));
                    m.put("app", rs.getString("app"));
                    m.put("endpoint", rs.getString("endpoint"));
                    m.put("caller", rs.getString("caller"));
                    m.put("role", rs.getString("role_name"));
                    m.put("database", rs.getString("db_name"));
                    m.put("sql", SqlTag.strip(rs.getString("query")));
                    m.put("calls", rs.getLong("calls"));
                    m.put("totalTimeMs", round(rs.getDouble("total_exec_time")));
                    m.put("meanTimeMs", round(rs.getDouble("mean_exec_time")));
                    m.put("maxTimeMs", round(rs.getDouble("max_exec_time")));
                    m.put("rows", rs.getLong("rows"));
                    m.put("sharedBlksHit", rs.getLong("shared_blks_hit"));
                    m.put("sharedBlksRead", rs.getLong("shared_blks_read"));
                    m.put("tempBlksWritten", rs.getLong("temp_blks_written"));
                    m.put("countersRestarted", rs.getBoolean("restarted"));
                    List<Map<String, Object>> attribution = attribution(c, target, since,
                            rs.getString("db_name"), rs.getString("role_name"), rs.getLong("queryid"));
                    if (!attribution.isEmpty()) {
                        // exact: every auto_explain entry carries its own tag
                        m.put("app", attribution.get(0).get("app"));
                        m.put("endpoint", attribution.get(0).get("endpoint"));
                        m.put("caller", attribution.get(0).get("caller"));
                        m.put("attributionSource", "auto_explain");
                    } else {
                        m.put("attributionSource", m.get("endpoint") == null ? "none" : "pg_stat_statements");
                    }
                    m.put("attribution", attribution);
                    out.add(m);
                }
            }
            return out;
        }
    }

    private static List<Map<String, Object>> attribution(Connection c, String target, Instant since, String db,
                                                         String role, long queryid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.app, a.endpoint, a.caller, sum(a.executions), sum(a.total_ms), max(a.max_ms)
                FROM fq_attribution a JOIN fq_snapshot sn ON sn.id = a.snapshot_id
                WHERE sn.target = ? AND sn.taken_at > ? AND a.db_name = ? AND a.role_name = ? AND a.queryid = ?
                GROUP BY 1, 2, 3 ORDER BY 4 DESC""")) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(since));
            ps.setString(3, db);
            ps.setString(4, role);
            ps.setLong(5, queryid);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app", rs.getString(1));
                    m.put("endpoint", rs.getString(2));
                    m.put("caller", rs.getString(3));
                    m.put("executions", rs.getLong(4));
                    m.put("loggedTimeMs", round(rs.getDouble(5)));
                    m.put("maxTimeMs", round(rs.getDouble(6)));
                    out.add(m);
                }
            }
            return out;
        }
    }

    /** Per-endpoint view built from auto_explain attributions (exact per execution). */
    public List<Map<String, Object>> endpoints(String target, Instant since, int statementsPerEndpoint) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                SELECT a.app, a.endpoint, sum(a.executions), sum(a.total_ms), count(DISTINCT a.queryid)
                FROM fq_attribution a JOIN fq_snapshot sn ON sn.id = a.snapshot_id
                WHERE sn.target = ? AND sn.taken_at > ?
                GROUP BY 1, 2 ORDER BY 4 DESC""")) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(since));
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app", rs.getString(1));
                    m.put("endpoint", rs.getString(2));
                    m.put("executions", rs.getLong(3));
                    m.put("loggedTimeMs", round(rs.getDouble(4)));
                    m.put("distinctStatements", rs.getLong(5));
                    m.put("statements", endpointStatements(c, target, since, rs.getString(1), rs.getString(2),
                            statementsPerEndpoint));
                    out.add(m);
                }
            }
            return out;
        }
    }

    private static List<Map<String, Object>> endpointStatements(Connection c, String target, Instant since, String app,
                                                                String endpoint, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.queryid, a.caller, sum(a.executions), sum(a.total_ms), max(s.query)
                FROM fq_attribution a JOIN fq_snapshot sn ON sn.id = a.snapshot_id
                LEFT JOIN fq_statement s ON s.target = sn.target AND s.db_name = a.db_name AND s.role_name = a.role_name
                                         AND s.queryid = a.queryid
                WHERE sn.target = ? AND sn.taken_at > ? AND a.app IS NOT DISTINCT FROM ? AND a.endpoint IS NOT DISTINCT FROM ?
                GROUP BY 1, 2 ORDER BY 3 DESC LIMIT ?""")) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(since));
            ps.setString(3, app);
            ps.setString(4, endpoint);
            ps.setInt(5, limit);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("queryid", Long.toString(rs.getLong(1)));
                    m.put("caller", rs.getString(2));
                    m.put("executions", rs.getLong(3));
                    m.put("loggedTimeMs", round(rs.getDouble(4)));
                    m.put("sql", SqlTag.strip(rs.getString(5)));
                    out.add(m);
                }
            }
            return out;
        }
    }

    /** Distinct plan shapes of one statement (most executed first). */
    public List<Map<String, Object>> plans(String target, long queryid) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                SELECT db_name, fingerprint, summary::text, plan::text, executions, first_seen, last_seen
                FROM fq_plan WHERE target = ? AND queryid = ? ORDER BY executions DESC""")) {
            ps.setString(1, target);
            ps.setLong(2, queryid);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("database", rs.getString(1));
                    m.put("fingerprint", rs.getString(2));
                    m.put("summary", JSON.readTree(rs.getString(3)));
                    m.put("executions", rs.getLong(5));
                    m.put("firstSeen", rs.getTimestamp(6).toInstant().toString());
                    m.put("lastSeen", rs.getTimestamp(7).toInstant().toString());
                    m.put("plan", JSON.readTree(rs.getString(4)));
                    out.add(m);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new SQLException("invalid stored plan", e);
            }
            return out;
        }
    }

    public List<Map<String, Object>> tableActivity(String target, Instant since) throws SQLException {
        String sql = """
                SELECT t.schema_name, t.table_name, sum(d.seq_scan) seq_scan, sum(d.seq_tup_read) seq_tup_read,
                       sum(d.idx_scan) idx_scan, sum(d.idx_tup_fetch) idx_tup_fetch,
                       sum(d.n_tup_ins) n_tup_ins, sum(d.n_tup_upd) n_tup_upd, sum(d.n_tup_del) n_tup_del,
                       (array_agg(d.n_live_tup ORDER BY sn.taken_at DESC))[1] n_live_tup
                FROM fq_table_delta d
                JOIN fq_snapshot sn ON sn.id = d.snapshot_id
                JOIN fq_table t ON t.id = d.table_id
                WHERE sn.target = ? AND sn.taken_at > ?
                GROUP BY t.id
                ORDER BY sum(d.seq_tup_read) DESC""";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setTimestamp(2, Timestamp.from(since));
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table", rs.getString(1) + "." + rs.getString(2));
                    m.put("seqScan", rs.getLong(3));
                    m.put("seqTupRead", rs.getLong(4));
                    m.put("idxScan", rs.getLong(5));
                    m.put("idxTupFetch", rs.getLong(6));
                    m.put("inserted", rs.getLong(7));
                    m.put("updated", rs.getLong(8));
                    m.put("deleted", rs.getLong(9));
                    m.put("liveTuples", rs.getLong(10));
                    out.add(m);
                }
            }
            return out;
        }
    }

    public List<Map<String, Object>> snapshots(String target, int limit) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                SELECT id, taken_at, previous_at, baseline, stats_reset_seen, dealloc_delta, statements, duration_ms,
                       log_entries, log_lag_bytes, log_unparsable,
                       (SELECT count(*) FROM fq_statement_delta d WHERE d.snapshot_id = sn.id) AS active
                FROM fq_snapshot sn WHERE target = ? ORDER BY taken_at DESC LIMIT ?""")) {
            ps.setString(1, target);
            ps.setInt(2, limit);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("takenAt", rs.getTimestamp("taken_at").toInstant().toString());
                    Timestamp prev = rs.getTimestamp("previous_at");
                    m.put("previousAt", prev == null ? null : prev.toInstant().toString());
                    m.put("baseline", rs.getBoolean("baseline"));
                    m.put("statsReset", rs.getBoolean("stats_reset_seen"));
                    m.put("evicted", rs.getLong("dealloc_delta"));
                    m.put("statements", rs.getInt("statements"));
                    m.put("activeStatements", rs.getLong("active"));
                    m.put("durationMs", round(rs.getDouble("duration_ms")));
                    m.put("planEntries", rs.getInt("log_entries"));
                    m.put("logLagBytes", rs.getLong("log_lag_bytes"));
                    m.put("unparsableLogLines", rs.getInt("log_unparsable"));
                    out.add(m);
                }
            }
            return out;
        }
    }

    private static void setInstant(PreparedStatement ps, int i, Instant v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(i, Timestamp.from(v));
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
