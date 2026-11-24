package io.github.anthonydeseille.flairquery.analyzer.prove;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A throw-away PostgreSQL 16 (Testcontainers) for one proof run. The schema and the data are prepared once in
 * a template database; every run (baseline, each candidate) gets its own clone, so a candidate that adds a
 * migration (an index...) never changes what the others see.
 */
final class ProofDatabase implements AutoCloseable {

    static final String TEMPLATE = "fq_template";
    static final String USER = "flairquery";
    static final String PASSWORD = "flairquery";

    private final PostgreSQLContainer<?> postgres;

    private ProofDatabase(PostgreSQLContainer<?> postgres) {
        this.postgres = postgres;
    }

    static ProofDatabase start(String image) {
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(image)
                .withDatabaseName(TEMPLATE)
                .withUsername(USER)
                .withPassword(PASSWORD)
                // throw-away data: durability off, it only slows the seeding down
                .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off", "-c", "synchronous_commit=off");
        pg.start();
        return new ProofDatabase(pg);
    }

    String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
    }

    Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), USER, PASSWORD);
    }

    /**
     * A connection for {@code EXPLAIN (GENERIC_PLAN)} of statements with {@code $n} parameters: simple query
     * protocol, otherwise the driver binds zero values to a statement that declares some and the server refuses.
     */
    Connection connectForPlans(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database) + "?preferQueryMode=simple", USER, PASSWORD);
    }

    /** Statistics for the planner, carried over to every clone. */
    void analyzeTemplate() throws SQLException {
        try (Connection c = connect(TEMPLATE); Statement st = c.createStatement()) {
            st.execute("VACUUM ANALYZE");
        }
    }

    /** A fresh copy of the template (dropped first if it exists). */
    void cloneTemplate(String database) throws SQLException {
        if (!database.matches("[a-z0-9_]+")) throw new IllegalArgumentException("database name " + database);
        try (Connection c = connect("postgres"); Statement st = c.createStatement()) {
            // CREATE DATABASE ... TEMPLATE needs the template to be idle
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + TEMPLATE
                    + "' AND pid <> pg_backend_pid()");
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE + " STRATEGY FILE_COPY");
        }
    }

    void drop(String database) throws SQLException {
        try (Connection c = connect("postgres"); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Override
    public void close() {
        postgres.stop();
    }
}
