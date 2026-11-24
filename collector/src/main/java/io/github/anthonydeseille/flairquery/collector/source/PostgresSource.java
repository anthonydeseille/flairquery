package io.github.anthonydeseille.flairquery.collector.source;

import io.github.anthonydeseille.flairquery.collector.config.Target;
import io.github.anthonydeseille.flairquery.collector.delta.Counters;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads statistics from the observed database with the read-only role. Only statistics views: never an
 * application table, never a write. The collector's own statements (current role) are excluded.
 */
public final class PostgresSource {

    /** Order of the statement counters stored in {@link Counters}. */
    public static final List<String> STATEMENT_COUNTERS = List.of(
            "calls", "total_exec_time", "rows", "shared_blks_hit", "shared_blks_read", "temp_blks_read", "temp_blks_written");
    /** Order of the table counters stored in {@link Counters}. */
    public static final List<String> TABLE_COUNTERS = List.of(
            "seq_scan", "seq_tup_read", "idx_scan", "idx_tup_fetch", "n_tup_ins", "n_tup_upd", "n_tup_del");

    private static final String STATEMENTS_SQL = """
            SELECT s.dbid, s.userid, s.queryid, s.toplevel, d.datname, r.rolname, s.query,
                   s.calls, s.total_exec_time, s.rows, s.shared_blks_hit, s.shared_blks_read,
                   s.temp_blks_read, s.temp_blks_written, s.max_exec_time
            FROM pg_stat_statements s
            LEFT JOIN pg_database d ON d.oid = s.dbid
            LEFT JOIN pg_roles r ON r.oid = s.userid
            WHERE s.queryid IS NOT NULL
              AND s.userid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
            """;
    private static final String INFO_SQL = "SELECT stats_reset, dealloc FROM pg_stat_statements_info";
    private static final String TABLES_SQL = """
            SELECT current_database(), relid, schemaname, relname,
                   coalesce(seq_scan, 0), coalesce(seq_tup_read, 0), coalesce(idx_scan, 0), coalesce(idx_tup_fetch, 0),
                   coalesce(n_tup_ins, 0), coalesce(n_tup_upd, 0), coalesce(n_tup_del, 0), coalesce(n_live_tup, 0)
            FROM pg_stat_user_tables
            """;

    /**
     * Only value-free columns of pg_stats: never most_common_vals, most_common_freqs, histogram_bounds or
     * the element variants, which contain data values.
     */
    private static final String COLUMN_STATS_SQL = """
            SELECT current_database(), s.schemaname, s.tablename, s.attname,
                   s.n_distinct, s.null_frac, s.correlation, s.avg_width, c.reltuples
            FROM pg_stats s
            JOIN pg_namespace n ON n.nspname = s.schemaname
            JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = s.tablename
            WHERE s.schemaname NOT IN ('pg_catalog', 'information_schema')
              AND s.tablename = ANY (?)
              AND NOT s.inherited
            ORDER BY 2, 3, 4
            """;

    /** Server settings read for sizing (phase 6). */
    public static final List<String> SIZING_SETTINGS = List.of(
            "shared_buffers", "effective_cache_size", "work_mem", "hash_mem_multiplier", "maintenance_work_mem",
            "temp_buffers", "max_connections", "superuser_reserved_connections", "reserved_connections",
            "max_worker_processes", "max_parallel_workers_per_gather", "random_page_cost", "temp_file_limit",
            "server_version_num");

    private static final String SETTINGS_SQL = """
            SELECT name, setting, unit, source, context, pending_restart, boot_val
            FROM pg_settings WHERE name = ANY (?) ORDER BY name
            """;
    private static final String DATABASE_SQL = """
            SELECT datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit, temp_files, temp_bytes,
                   deadlocks, stats_reset
            FROM pg_stat_database WHERE datname = current_database()
            """;
    /** Client connections only (no autovacuum, walwriter...), grouped; never the query text. */
    private static final String ACTIVITY_SQL = """
            SELECT coalesce(usename, ''), coalesce(application_name, ''), coalesce(state, ''), count(*)
            FROM pg_stat_activity WHERE backend_type = 'client backend'
            GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
            """;

    private final Target target;

    public PostgresSource(Target target) {
        this.target = target;
    }

    public Target target() {
        return target;
    }

    public record Reading(StatementsInfo info, List<StatementRow> statements, List<TableRow> tables) { }

    /** One consistent-enough reading (read-only transaction). */
    public Reading read() throws SQLException {
        try (Connection c = connect()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                StatementsInfo info;
                try (ResultSet rs = st.executeQuery(INFO_SQL)) {
                    rs.next();
                    Timestamp reset = rs.getTimestamp(1);
                    info = new StatementsInfo(reset == null ? null : reset.toInstant(), rs.getLong(2));
                }
                List<StatementRow> statements = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(STATEMENTS_SQL)) {
                    while (rs.next()) {
                        statements.add(new StatementRow(
                                new StatementKey(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBoolean(4)),
                                rs.getString(5), rs.getString(6), rs.getString(7),
                                Counters.of(rs.getLong(8), rs.getDouble(9), rs.getLong(10), rs.getLong(11),
                                        rs.getLong(12), rs.getLong(13), rs.getLong(14)),
                                rs.getDouble(15)));
                    }
                }
                List<TableRow> tables = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(TABLES_SQL)) {
                    while (rs.next()) {
                        tables.add(new TableRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                Counters.of(rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                                        rs.getLong(9), rs.getLong(10), rs.getLong(11)),
                                rs.getLong(12)));
                    }
                }
                c.commit();
                return new Reading(info, statements, tables);
            }
        }
    }

    /** Value-free statistics of the given tables (all user schemas). Read-only transaction. */
    public List<ColumnStatsRow> readColumnStats(java.util.Collection<String> tables) throws SQLException {
        if (tables.isEmpty()) return List.of();
        try (Connection c = connect()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = c.prepareStatement(COLUMN_STATS_SQL)) {
                ps.setArray(1, c.createArrayOf("text", tables.toArray()));
                List<ColumnStatsRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ColumnStatsRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                floatOrNull(rs, 5), floatOrNull(rs, 6), floatOrNull(rs, 7),
                                rs.getObject(8) == null ? null : rs.getInt(8), floatOrNull(rs, 9)));
                    }
                }
                c.commit();
                return out;
            }
        }
    }

    /**
     * Sizing signals of the server (read-only): the memory and connection settings, the database's cumulative
     * counters (temporary files, cache hits) and the client connections by role, application and state.
     */
    public java.util.Map<String, Object> readServer() throws SQLException {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try (Connection c = connect()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement(SETTINGS_SQL)) {
                ps.setArray(1, c.createArrayOf("text", SIZING_SETTINGS.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.util.Map<String, Object> s = new java.util.LinkedHashMap<>();
                        s.put("setting", rs.getString(2));
                        s.put("unit", rs.getString(3));
                        s.put("bytes", settingBytes(rs.getString(2), rs.getString(3)));
                        s.put("source", rs.getString(4));
                        s.put("context", rs.getString(5));
                        s.put("pendingRestart", rs.getBoolean(6));
                        s.put("default", rs.getString(7));
                        settings.put(rs.getString(1), s);
                    }
                }
            }
            out.put("settings", settings);
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery(DATABASE_SQL)) {
                    if (rs.next()) {
                        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
                        d.put("datname", rs.getString(1));
                        d.put("numbackends", rs.getLong(2));
                        d.put("xactCommit", rs.getLong(3));
                        d.put("xactRollback", rs.getLong(4));
                        d.put("blksRead", rs.getLong(5));
                        d.put("blksHit", rs.getLong(6));
                        d.put("tempFiles", rs.getLong(7));
                        d.put("tempBytes", rs.getLong(8));
                        d.put("deadlocks", rs.getLong(9));
                        Timestamp reset = rs.getTimestamp(10);
                        d.put("statsReset", reset == null ? null : reset.toInstant().toString());
                        out.put("database", d);
                    }
                }
                List<java.util.Map<String, Object>> conns = new ArrayList<>();
                long total = 0;
                try (ResultSet rs = st.executeQuery(ACTIVITY_SQL)) {
                    while (rs.next()) {
                        java.util.Map<String, Object> a = new java.util.LinkedHashMap<>();
                        a.put("role", rs.getString(1));
                        a.put("application", rs.getString(2));
                        a.put("state", rs.getString(3));
                        a.put("count", rs.getLong(4));
                        total += rs.getLong(4);
                        conns.add(a);
                    }
                }
                out.put("clientConnections", total);
                out.put("connections", conns);
            }
            c.commit();
        }
        return out;
    }

    /** pg_settings value in bytes for memory units ("8kB" x 4096 = 32 MB), null otherwise or when negative. */
    public static Long settingBytes(String setting, String unit) {
        if (setting == null || unit == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d*)(B|kB|MB|GB|TB)").matcher(unit);
        if (!m.matches()) return null;
        long factor = m.group(1).isEmpty() ? 1 : Long.parseLong(m.group(1));
        factor *= switch (m.group(2)) {
            case "kB" -> 1024L;
            case "MB" -> 1024L * 1024;
            case "GB" -> 1024L * 1024 * 1024;
            case "TB" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        try {
            long v = Long.parseLong(setting);
            return v < 0 ? null : v * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float floatOrNull(ResultSet rs, int i) throws SQLException {
        float v = rs.getFloat(i);
        return rs.wasNull() ? null : v;
    }

    Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", target.user());
        p.setProperty("password", target.password());
        p.setProperty("ApplicationName", "flairquery-collector");
        p.setProperty("readOnly", "true");
        p.setProperty("connectTimeout", "10");
        p.setProperty("socketTimeout", "60");
        return DriverManager.getConnection(target.jdbcUrl(), p);
    }
}
