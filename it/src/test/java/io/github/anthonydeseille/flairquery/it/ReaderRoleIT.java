package io.github.anthonydeseille.flairquery.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.sql.SqlTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fq_reader role (what the phase 2 collector connects with) can read statistics and the auto_explain
 * JSON log, and nothing it should not.
 */
class ReaderRoleIT {

    private static final String TAG = "/* fq:app=it-reader,endpoint=GET /probe/{id},caller=com.x.Probe#run:7 */ ";

    @BeforeAll
    static void schemaAndTraffic() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("reader-role", false, DemoAppProcess.env())) {
            ItEnvironment.ensureSeeded(); // Liquibase created the tables as the application role
        }
        ItEnvironment.exec(TAG + "SELECT count(*) FROM author WHERE id < 10");
    }

    @Test
    void readsStatisticsViews() throws Exception {
        try (Connection c = ItEnvironment.connectAsReader(); Statement st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM pg_stat_statements WHERE query LIKE '/* fq:app=it-reader%'"))
                    .isEqualTo("1");
            assertThat(scalar(st, "SELECT count(*) FROM pg_stat_user_tables WHERE relname = 'review'")).isEqualTo("1");
            // value-free statistics are visible
            assertThat(scalar(st, "SELECT count(*) FROM pg_stats WHERE tablename = 'review' AND attname = 'reviewer_name'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void readsAutoExplainJsonLogWithQueryIdAndTag() throws Exception {
        Thread.sleep(500); // logging collector flush
        List<JsonNode> entries = new ArrayList<>();
        try (Connection c = ItEnvironment.connectAsReader(); Statement st = c.createStatement();
             ResultSet files = st.executeQuery("SELECT name FROM pg_ls_logdir() WHERE name LIKE '%.json'")) {
            List<String> names = new ArrayList<>();
            while (files.next()) names.add(files.getString(1));
            assertThat(names).isNotEmpty();
            for (String name : names) {
                try (Statement read = c.createStatement();
                     ResultSet rs = read.executeQuery("SELECT pg_read_file('log/" + name + "', 0, "
                             + "(pg_stat_file('log/" + name + "')).size)")) {
                    rs.next();
                    for (String line : rs.getString(1).split("\n")) {
                        if (line.contains("fq:app=it-reader")) entries.add(ItEnvironment.JSON.readTree(line));
                    }
                }
            }
        }
        JsonNode entry = entries.stream().filter(e -> e.path("message").asText().contains("plan:")).findFirst().orElseThrow();
        assertThat(entry.path("query_id").asLong()).isNotZero();
        String message = entry.get("message").asText();
        JsonNode plan = ItEnvironment.JSON.readTree(message.substring(message.indexOf('{')));
        SqlTag.FqTag tag = SqlTag.parse(plan.get("Query Text").asText()).orElseThrow();
        assertThat(tag.endpoint()).isEqualTo("GET /probe/{id}");
        assertThat(tag.caller()).isEqualTo("com.x.Probe#run:7");
    }

    @Test
    void cannotWriteNorReadOutsideTheLogDirectory() throws Exception {
        try (Connection c = ItEnvironment.connectAsReader(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("INSERT INTO publisher (id, name) VALUES (999999, 'x')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.execute("CREATE TABLE fq_should_fail (id int)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.execute("SELECT pg_read_file('/etc/passwd', 0, 10)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("absolute path not allowed");
            assertThatThrownBy(() -> st.execute("SELECT pg_stat_statements_reset()"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
