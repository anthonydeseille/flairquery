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
