package io.github.anthonydeseille.flairquery.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.PGConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One PostgreSQL 16 container for the whole run (pg_stat_statements + auto_explain preloaded), seeded once
 * with corpus profile S through COPY, exactly like corpus/load.sh does.
 */
final class ItEnvironment {

    static final String PROFILE = "S";
    static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static boolean seeded;
    private static JsonNode expect;

    private ItEnvironment() { }

    static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            // same configuration and role as the Compose and kind environments (deploy/postgres)
            Path pgDir = Path.of(System.getProperty("fq.postgres.dir", "../deploy/postgres"));
            postgres = new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("flairquery")
                    .withUsername("flairquery")
                    .withPassword("flairquery")
                    .withCopyFileToContainer(MountableFile.forHostPath(pgDir.resolve("postgresql-fq.conf")),
                            "/etc/postgresql/postgresql.conf")
                    .withCopyFileToContainer(MountableFile.forHostPath(pgDir.resolve("init-fq.sql")),
                            "/docker-entrypoint-initdb.d/10-init-fq.sql")
                    .withCommand("postgres", "-c", "config_file=/etc/postgresql/postgresql.conf", "-c", "fsync=off")
                    // server logs go to JSON files (logging_collector), not to the container output: the
                    // default "ready to accept connections" log wait would never match. TCP readiness only
                    // succeeds once the real server runs (the init-time server listens on the socket only).
                    .waitingFor(Wait.forSuccessfulCommand("pg_isready -h 127.0.0.1 -U flairquery -d flairquery")
                            .withStartupTimeout(Duration.ofMinutes(2)));
            postgres.start();
        }
        return postgres;
    }

    static String jdbcUrl() {
        return postgres().getJdbcUrl();
    }

    /** Connection as the read-only FlairQuery role (fq_reader). */
    static Connection connectAsReader() throws SQLException {
        return DriverManager.getConnection(postgres().getJdbcUrl(), "fq_reader", "fq_reader");
    }

    static Connection connect() throws SQLException {
        PostgreSQLContainer<?> pg = postgres();
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    static void exec(String sql) {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    static long count(String sql) {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    static List<String> strings(String sql) {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return out;
    }

    static void resetPgStatStatements() {
        exec("SELECT pg_stat_statements_reset()");
    }

    /** Queries that reached PostgreSQL and carry a FlairQuery tag. */
    static List<String> taggedStatements() {
        return strings("SELECT query FROM pg_stat_statements WHERE query LIKE '/* fq:%' ORDER BY calls DESC");
    }

    /** Corpus facts (`generate.py --expect`). */
    static synchronized JsonNode expect() {
        if (expect == null) {
            try {
                expect = JSON.readTree(python("--profile", PROFILE, "--expect"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return expect;
    }

    /** Loads profile S once (schema must exist: an application started first and ran Liquibase). */
    static synchronized void ensureSeeded() {
        if (seeded) {
            return;
        }
        if (count("SELECT count(*) FROM author") == 0) {
            for (String table : List.of("publisher", "author", "book", "review")) {
                copy(table);
            }
            for (String table : List.of("publisher", "author", "book", "review")) {
                exec("SELECT setval(pg_get_serial_sequence('" + table + "','id'), (SELECT max(id) FROM " + table + "))");
            }
            exec("ANALYZE");
        }
        seeded = true;
    }

    private static void copy(String table) {
        try (Connection c = connect()) {
            String columns = new String(python("--profile", PROFILE, "--columns", table)).trim();
            Process p = pythonProcess("--profile", PROFILE, "--table", table);
            try (InputStream csv = p.getInputStream()) {
                c.unwrap(PGConnection.class).getCopyAPI()
                        .copyIn("COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)", csv);
            }
            if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException("generate.py failed for " + table);
            }
        } catch (Exception e) {
            throw new IllegalStateException("COPY " + table, e);
        }
    }

    private static byte[] python(String... args) {
        try {
            Process p = pythonProcess(args);
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(1, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException("generate.py " + String.join(" ", args) + " failed");
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Process pythonProcess(String... args) throws IOException {
        Path script = Path.of(System.getProperty("fq.corpus.dir", "../corpus"), "generate.py");
        if (!Files.exists(script)) {
            throw new IllegalStateException("corpus generator not found: " + script.toAbsolutePath());
        }
        List<String> cmd = new ArrayList<>(List.of("python3", "-I", script.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
}
