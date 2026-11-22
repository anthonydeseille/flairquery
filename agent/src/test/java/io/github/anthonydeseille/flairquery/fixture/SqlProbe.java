package io.github.anthonydeseille.flairquery.fixture;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * "Application" code (in a base package) that runs SQL; returns the text received by the H2 driver
 * (see {@link DriverSqlRecorder}).
 */
public class SqlProbe {

    /** Statement as written by the application (before any tag); {@code %s} is a unique marker. */
    public static final String PROBE_SQL = "SELECT 1 AS FQ_PROBE, '%s' AS MARKER";

    private final DataSource dataSource;
    private String lastProbeSql;

    public SqlProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String lastProbeSql() {
        return lastProbeSql;
    }

    public String preparedSelfText() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String sql = newProbe(c);
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            return recorded(c);
        }
    }

    public String statementSelfText() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String sql = newProbe(c);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
            }
            return recorded(c);
        }
    }

    public int selectOne() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String newProbe(Connection c) {
        lastProbeSql = PROBE_SQL.formatted(UUID.randomUUID());
        return lastProbeSql;
    }

    private String recorded(Connection c) {
        String marker = lastProbeSql.substring(lastProbeSql.indexOf('\'') + 1, lastProbeSql.lastIndexOf('\''));
        return DriverSqlRecorder.lastContaining(marker);
    }
}
