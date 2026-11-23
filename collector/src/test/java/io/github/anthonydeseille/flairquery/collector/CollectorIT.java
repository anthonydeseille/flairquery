package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.config.Target;
import io.github.anthonydeseille.flairquery.collector.http.CollectorHttpServer;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;
import io.github.anthonydeseille.flairquery.collector.store.Store;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real collection cycles: observed PostgreSQL (shared FlairQuery config) + fq-store. */
class CollectorIT {

    private static final String TAG = "/* fq:app=demo,endpoint=GET /authors,caller=com.x.AuthorController#toDto:47 */ ";

    static PostgreSQLContainer<?> observed;
    static PostgreSQLContainer<?> fqStore;
    static Store store;
    Collector collector;

    @BeforeAll
    static void start() throws Exception {
        Path pg = Path.of(System.getProperty("fq.postgres.dir", "../deploy/postgres"));
        observed = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("flairquery").withUsername("flairquery").withPassword("flairquery")
                .withCopyFileToContainer(MountableFile.forHostPath(pg.resolve("postgresql-fq.conf")), "/etc/postgresql/postgresql.conf")
                .withCopyFileToContainer(MountableFile.forHostPath(pg.resolve("init-fq.sql")), "/docker-entrypoint-initdb.d/10-init-fq.sql")
                .withCommand("postgres", "-c", "config_file=/etc/postgresql/postgresql.conf", "-c", "fsync=off")
                .waitingFor(Wait.forSuccessfulCommand("pg_isready -h 127.0.0.1 -U flairquery -d flairquery")
                        .withStartupTimeout(Duration.ofMinutes(2)));
        fqStore = new PostgreSQLContainer<>("postgres:16").withDatabaseName("fqstore").withUsername("fqstore").withPassword("fqstore");
        observed.start();
        fqStore.start();
        app("CREATE TABLE author (id bigint primary key, name text)",
            "INSERT INTO author SELECT g, 'a' || g FROM generate_series(1, 1000) g");
        store = new Store(fqStore.getJdbcUrl(), "fqstore", "fqstore");
        store.migrate();
    }

    @AfterAll
    static void stop() {
        if (observed != null) observed.stop();
        if (fqStore != null) fqStore.stop();
    }

    @BeforeEach
    void freshStoreAndStats() throws Exception {
        try (Connection c = store.connect(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE fq_snapshot, fq_statement, fq_table CASCADE");
        }
        app("SELECT pg_stat_statements_reset()");
        Thread.sleep(50);
        collector = new Collector(new PostgresSource(new Target("it", observed.getJdbcUrl(), "fq_reader", "fq_reader")),
                store, Duration.ofHours(72), Clock.systemUTC());
    }

    /** Runs SQL as the application role (not the collector's read-only role). */
    static void app(String... sqls) throws Exception {
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery");
             Statement st = c.createStatement()) {
            for (String sql : sqls) st.execute(sql);
        }
    }

    static void tagged(int times) throws Exception {
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery")) {
            for (int i = 0; i < times; i++) {
                try (var ps = c.prepareStatement(TAG + "SELECT name FROM author WHERE id = ?")) {
                    ps.setLong(1, i + 1);
                    ps.executeQuery().close();
                }
            }
        }
    }

    @Test
    void firstCycleIsABaselineThenDeltasAreExact() throws Exception {
        tagged(7);
        assertThat(collector.collectOnce().baseline()).isTrue();
        assertThat(store.topStatements("it", Instant.EPOCH, 10, Store.Order.CALLS)).isEmpty();

        tagged(5);
        collector.collectOnce();
        tagged(3);
        collector.collectOnce();

        Map<String, Object> row = authorLookup();
        assertThat(row).containsEntry("calls", 8L) // 5 + 3: the 7 calls before the baseline are not counted
                .containsEntry("app", "demo")
                .containsEntry("endpoint", "GET /authors")
                .containsEntry("caller", "com.x.AuthorController#toDto:47");
        assertThat((String) row.get("sql")).doesNotContain("fq:").contains("$1");
    }

    @Test
    void globalResetNeverProducesNegativeOrLostDeltas() throws Exception {
        collector.collectOnce();            // baseline
        tagged(20);
        collector.collectOnce();            // +20
        app("SELECT pg_stat_statements_reset()");
        tagged(4);
        Store.Cycle afterReset = collector.collectOnce();   // reset observed: +4, not 4 - 20

        assertThat(afterReset.statsResetSeen()).isTrue();
        assertThat(authorLookup()).containsEntry("calls", 24L);
    }

    @Test
    void collectorOwnStatementsAreExcludedAndItNeverWrites() throws Exception {
        collector.collectOnce();
        collector.collectOnce();
        collector.collectOnce();

        try (Connection c = store.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM fq_statement WHERE role_name = 'fq_reader'")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'flairquery-collector'"
                     + " AND state = 'idle in transaction'")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero(); // read transactions are closed
        }
    }

    @Test
    void tableSeqScansAreMeasured() throws Exception {
        collector.collectOnce();
        app("SELECT count(*) FROM author WHERE name = 'a42'", "SELECT count(*) FROM author WHERE name = 'a43'");
        Thread.sleep(600); // table statistics are flushed by backends at transaction end (stats_fetch_consistency)
        collector.collectOnce();

        Map<String, Object> author = store.tableActivity("it", Instant.EPOCH).stream()
                .filter(t -> t.get("table").equals("public.author")).findFirst().orElseThrow();
        assertThat((Long) author.get("seqScan")).isGreaterThanOrEqualTo(2L);
        assertThat((Long) author.get("seqTupRead")).isGreaterThanOrEqualTo(2000L);
    }

    @Test
    void httpApi() throws Exception {
        collector.collectOnce();
        tagged(6);
        collector.collectOnce();
        CollectorHttpServer http = new CollectorHttpServer(collector, store);
        int port = http.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String queries = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/fq/collector/queries?window=1h&order=calls")).build(), HttpResponse.BodyHandlers.ofString()).body();
            String snapshots = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/fq/collector/snapshots")).build(), HttpResponse.BodyHandlers.ofString()).body();
            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/fq/collector/queries?window=forever")).build(), HttpResponse.BodyHandlers.ofString());
            String metrics = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body();

            assertThat(queries).startsWith("{\"schemaVersion\":1,\"target\":\"it\"")
                    .contains("\"endpoint\":\"GET /authors\"", "\"calls\":6");
            assertThat(snapshots).contains("\"baseline\":true", "\"baseline\":false");
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(metrics).contains("fq_collector_cycles{target=\"it\"} 2");
        } finally {
            http.stop();
        }
    }

    private Map<String, Object> authorLookup() throws Exception {
        List<Map<String, Object>> top = store.topStatements("it", Instant.EPOCH, 50, Store.Order.CALLS);
        return top.stream().filter(r -> String.valueOf(r.get("sql")).contains("FROM author WHERE id = $1"))
                .findFirst().orElseThrow(() -> new AssertionError("author lookup not collected: " + top));
    }
}
