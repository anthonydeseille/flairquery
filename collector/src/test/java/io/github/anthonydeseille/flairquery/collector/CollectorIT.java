package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.config.Target;
import io.github.anthonydeseille.flairquery.collector.http.CollectorHttpServer;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;
import io.github.anthonydeseille.flairquery.collector.source.SqlLogPlanSource;
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
            st.execute("TRUNCATE fq_snapshot, fq_statement, fq_table, fq_plan, fq_log_cursor, fq_mark, fq_column_stats CASCADE");
        }
        app("SELECT pg_stat_statements_reset()");
        Thread.sleep(50);
        Target target = new Target("it", observed.getJdbcUrl(), "fq_reader", "fq_reader");
        collector = new Collector(new PostgresSource(target), new SqlLogPlanSource(target, 64L * 1024 * 1024),
                store, Duration.ofHours(72), Duration.ZERO, Clock.systemUTC()); // pg_stats refreshed explicitly
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
    void autoExplainSplitsOneQueryidAcrossTheEndpointsThatReallyRanIt() throws Exception {
        String sql = "SELECT name FROM author WHERE id = ?";
        String tagA = "/* fq:app=demo,endpoint=GET /books,caller=com.x.BookService#firstBooks:23 */ ";
        String tagB = "/* fq:app=demo,endpoint=GET /books/titles,caller=com.x.BookService#titlesOfAuthor:29 */ ";
        collector.collectOnce();                         // baseline: existing log content is skipped
        run(tagA + sql, 9);
        run(tagB + sql, 4);
        Thread.sleep(300);                               // logging collector flush
        collector.collectOnce();

        Map<String, Object> row = authorLookup();
        // pg_stat_statements: ONE entry (same queryid), whose text holds only the first tag seen
        assertThat(row).containsEntry("calls", 13L).containsEntry("attributionSource", "auto_explain");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attribution = (List<Map<String, Object>>) row.get("attribution");
        assertThat(attribution).extracting(a -> a.get("endpoint"), a -> a.get("executions"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET /books", 9L),
                        org.assertj.core.groups.Tuple.tuple("GET /books/titles", 4L));
        assertThat(attribution.get(1).get("caller")).isEqualTo("com.x.BookService#titlesOfAuthor:29");

        // per-endpoint view, exact
        Map<String, Object> titles = store.endpoints("it", Instant.EPOCH, 5).stream()
                .filter(e -> "GET /books/titles".equals(e.get("endpoint"))).findFirst().orElseThrow();
        assertThat(titles).containsEntry("executions", 4L);

        // a second cycle does not count the same log lines twice
        collector.collectOnce();
        assertThat(store.endpoints("it", Instant.EPOCH, 5).stream()
                .filter(e -> "GET /books".equals(e.get("endpoint"))).findFirst().orElseThrow())
                .containsEntry("executions", 9L);
    }

    @Test
    void plansShowTheSequentialScanAndItsFilter() throws Exception {
        collector.collectOnce();
        app(TAG + "SELECT count(*) FROM author WHERE name = 'a42'");
        Thread.sleep(300);
        collector.collectOnce();

        String queryid = store.topStatements("it", Instant.EPOCH, 50, Store.Order.CALLS).stream()
                .filter(r -> String.valueOf(r.get("sql")).contains("WHERE name = $1"))
                .map(r -> (String) r.get("queryid")).findFirst().orElseThrow();
        List<Map<String, Object>> plans = store.plans("it", Long.parseLong(queryid));
        assertThat(plans).hasSize(1);
        String summary = plans.get(0).get("summary").toString();
        assertThat(summary).contains("\"relation\":\"author\"", "name");
        try (Connection c = store.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM fq_plan WHERE plan::text LIKE '%a42%' OR summary::text LIKE '%a42%'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("no data value in fq-store").isZero();
        }
    }

    @Test
    void plansAreAttachedToTheirOwnStatementInsideExplicitTransactions() throws Exception {
        // pgjdbc + explicit transaction: a statement's plan is logged when its portal closes, i.e. at the next
        // statement or at COMMIT, with THAT statement's query_id. The collector must not trust it.
        String byId = "/* fq:app=demo,endpoint=GET /a,caller=com.x.A#byId:1 */ SELECT name FROM author WHERE id = ?";
        String byName = "/* fq:app=demo,endpoint=GET /b,caller=com.x.B#byName:2 */ SELECT id FROM author WHERE name = ?";
        collector.collectOnce();
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery")) {
            c.setAutoCommit(false);
            for (int i = 0; i < 3; i++) {
                try (var ps = c.prepareStatement(byId)) {
                    ps.setLong(1, i + 1);
                    ps.executeQuery().close();
                }
                try (var ps = c.prepareStatement(byName)) {
                    ps.setString(1, "a" + (i + 1));
                    ps.executeQuery().close();
                }
                c.commit();
            }
        }
        Thread.sleep(300);
        collector.collectOnce();

        List<Map<String, Object>> top = store.topStatements("it", Instant.EPOCH, 50, Store.Order.CALLS);
        long idQuery = queryidOf(top, "FROM author WHERE id = $1");
        long nameQuery = queryidOf(top, "FROM author WHERE name = $1");
        assertThat(store.plans("it", idQuery)).singleElement()
                .satisfies(p -> assertThat(p.get("summary").toString()).contains("Index Scan").doesNotContain("Seq Scan"));
        assertThat(store.plans("it", nameQuery)).singleElement()
                .satisfies(p -> assertThat(p.get("summary").toString()).contains("Seq Scan"));
        Map<String, Object> byNameRow = top.stream().filter(r -> Long.parseLong((String) r.get("queryid")) == nameQuery)
                .findFirst().orElseThrow();
        assertThat(byNameRow).containsEntry("attributionSource", "auto_explain").containsEntry("endpoint", "GET /b");
        assertThat(store.endpoints("it", Instant.EPOCH, 5)).extracting(e -> e.get("endpoint") + "=" + e.get("executions"))
                .contains("GET /a=3", "GET /b=3");
    }

    static long queryidOf(List<Map<String, Object>> top, String sqlPart) {
        return top.stream().filter(r -> String.valueOf(r.get("sql")).contains(sqlPart))
                .map(r -> Long.parseLong((String) r.get("queryid"))).findFirst()
                .orElseThrow(() -> new AssertionError(sqlPart + " not collected: " + top));
    }

    static void run(String sql, int times) throws Exception {
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery")) {
            for (int i = 0; i < times; i++) {
                try (var ps = c.prepareStatement(sql)) {
                    ps.setLong(1, i + 1);
                    ps.executeQuery().close();
                }
            }
        }
    }

    @Test
    void httpApi() throws Exception {
        collector.collectOnce();
        tagged(6);
        Thread.sleep(300);
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
            String endpoints = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/fq/collector/endpoints?window=1h")).build(), HttpResponse.BodyHandlers.ofString()).body();
            HttpResponse<String> noQueryid = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/fq/collector/plans")).build(), HttpResponse.BodyHandlers.ofString());
            String metrics = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body();

            assertThat(queries).startsWith("{\"schemaVersion\":1,\"target\":\"it\"")
                    .contains("\"endpoint\":\"GET /authors\"", "\"calls\":6");
            assertThat(snapshots).contains("\"baseline\":true", "\"baseline\":false");
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(metrics).contains("fq_collector_cycles{target=\"it\"} 2", "fq_collector_log_lag_bytes");
            assertThat(endpoints).contains("\"endpoint\":\"GET /authors\"", "\"executions\":6");
            assertThat(noQueryid.statusCode()).isEqualTo(400);
        } finally {
            http.stop();
        }
    }

    @Test
    void columnStatisticsAreValueFreeAndLimitedToTablesSeen() throws Exception {
        app("CREATE TABLE IF NOT EXISTS never_queried (id bigint, secret text)",
            "INSERT INTO never_queried VALUES (1, 'top-secret')", "ANALYZE author", "ANALYZE never_queried");
        collector.collectOnce();
        app(TAG + "SELECT count(*) FROM author WHERE name = 'a42'");
        Thread.sleep(300);
        collector.collectOnce();

        assertThat(collector.refreshColumnStats(Instant.now())).isPositive();

        List<Map<String, Object>> columns = store.columnStats("it", null);
        assertThat(columns).extracting(c -> c.get("table")).containsOnly("public.author");  // only tables seen
        Map<String, Object> name = columns.stream().filter(c -> c.get("column").equals("name")).findFirst().orElseThrow();
        assertThat(name).containsEntry("nDistinct", -1.0)           // 'a' || g: unique
                .containsEntry("estimatedRows", 1000L)
                .containsEntry("estimatedDistinct", 1000L)
                .containsEntry("rowsPerValue", 1.0)
                .containsEntry("nullFrac", 0.0);
        assertThat(columns.toString()).doesNotContain("a42", "a1,", "top-secret");   // no data value stored
        app("DROP TABLE never_queried");
    }

    @Test
    void aTableSeenAfterTheFirstStatisticsReadDoesNotWaitForTheInterval() throws Exception {
        Target target = new Target("it", observed.getJdbcUrl(), "fq_reader", "fq_reader");
        Collector hourly = new Collector(new PostgresSource(target), new SqlLogPlanSource(target, 64L * 1024 * 1024),
                store, Duration.ofHours(72), Duration.ofHours(1), Clock.systemUTC());
        app("DROP TABLE IF EXISTS late_table", "ANALYZE author");
        hourly.collectOnce();                                   // baseline
        app(TAG + "SELECT count(*) FROM author WHERE name = 'a1'");
        Thread.sleep(300);
        hourly.collectOnce();                                   // first pg_stats read: author only
        assertThat(store.columnStatsTables("it")).containsExactly("author");

        app("CREATE TABLE late_table (id bigint, label text)",
            "INSERT INTO late_table SELECT g, 'l' || (g % 10) FROM generate_series(1, 500) g", "ANALYZE late_table",
            TAG + "SELECT count(*) FROM late_table WHERE label = 'l1'");
        Thread.sleep(300);
        hourly.collectOnce();                                   // interval not elapsed, but late_table is new

        assertThat(store.columnStatsTables("it")).contains("author", "late_table");
        assertThat(store.columnStats("it", "late_table")).anySatisfy(c ->
                assertThat(c).containsEntry("column", "label").containsEntry("estimatedDistinct", 10L));
        app("DROP TABLE late_table");
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksAndCompareShowAnIndexFix() throws Exception {
        app("DROP TABLE IF EXISTS review_it",
            "CREATE TABLE review_it (id bigint primary key, reviewer text, body text)",
            "INSERT INTO review_it SELECT g, 'r' || (g % 5000), repeat('x', 200) FROM generate_series(1, 30000) g",
            "ANALYZE review_it");
        String search = "/* fq:app=demo,endpoint=GET /reviews/search,caller=com.x.ReviewController#search:31 */ "
                + "SELECT id FROM review_it WHERE reviewer = ?";
        collector.collectOnce();                              // baseline
        assertThat(collector.mark("start", "trap 4 before the fix")).isPresent();
        runText(search, 5);
        Thread.sleep(300);
        assertThat(collector.mark("fix", null)).isPresent();
        assertThat(collector.mark("fix", null)).isEmpty();    // names are unique
        app("CREATE INDEX review_it_reviewer ON review_it (reviewer)", "ANALYZE review_it");
        runText(search, 5);
        Thread.sleep(300);
        collector.mark("end", null);

        List<Store.Mark> marks = store.marks("it");
        Map<String, Object> c = io.github.anthonydeseille.flairquery.collector.compare.Comparison.of(
                store.periodData("it", io.github.anthonydeseille.flairquery.collector.compare.Periods.resolve("start", "before", marks, Instant.now())),
                store.periodData("it", io.github.anthonydeseille.flairquery.collector.compare.Periods.resolve("fix", "after", marks, Instant.now())), 20);

        Map<String, Object> row = ((List<Map<String, Object>>) c.get("statements")).stream()
                .filter(s -> String.valueOf(s.get("sql")).contains("FROM review_it WHERE reviewer")).findFirst().orElseThrow();
        assertThat(row).containsEntry("status", "both").containsEntry("endpoints", List.of("GET /reviews/search"));
        assertThat((Map<String, Object>) row.get("before")).containsEntry("calls", 5L);
        assertThat((Map<String, Object>) row.get("after")).containsEntry("calls", 5L);   // nothing leaks across the mark
        Map<String, Object> plans = (Map<String, Object>) row.get("plans");
        assertThat(plans.get("changed")).isEqualTo(true);
        assertThat(plans.get("before").toString()).contains("review_it");             // Seq Scan on review_it
        assertThat(plans.get("after").toString()).contains("\"seqScans\":[]");          // index used
        long blocksBefore = (Long) ((Map<String, Object>) row.get("before")).get("sharedBlksHit")
                + (Long) ((Map<String, Object>) row.get("before")).get("sharedBlksRead");
        long blocksAfter = (Long) ((Map<String, Object>) row.get("after")).get("sharedBlksHit")
                + (Long) ((Map<String, Object>) row.get("after")).get("sharedBlksRead");
        assertThat(blocksAfter * 10).isLessThan(blocksBefore);
        app("DROP TABLE review_it");
    }

    static void runText(String sql, int times) throws Exception {
        try (Connection c = DriverManager.getConnection(observed.getJdbcUrl(), "flairquery", "flairquery")) {
            for (int i = 0; i < times; i++) {
                try (var ps = c.prepareStatement(sql)) {
                    ps.setString(1, "r" + (i + 1));
                    ps.executeQuery().close();
                }
            }
        }
    }

    @Test
    void httpMarksCompareAndColumns() throws Exception {
        collector.collectOnce();
        CollectorHttpServer http = new CollectorHttpServer(collector, store);
        int port = http.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + port;
            HttpResponse<String> created = post(client, base + "/fq/collector/marks", "{\"name\":\"before\",\"note\":\"n\"}");
            HttpResponse<String> duplicate = post(client, base + "/fq/collector/marks", "{\"name\":\"before\"}");
            HttpResponse<String> badName = post(client, base + "/fq/collector/marks", "{\"name\":\"a b\"}");
            HttpResponse<String> badJson = post(client, base + "/fq/collector/marks", "{");
            tagged(3);
            Thread.sleep(300);
            post(client, base + "/fq/collector/marks", "{\"name\":\"after\"}");
            String marks = get(client, base + "/fq/collector/marks").body();
            HttpResponse<String> compare = get(client, base + "/fq/collector/compare?before=before&after=after");
            HttpResponse<String> unknown = get(client, base + "/fq/collector/compare?before=nope&after=after");
            HttpResponse<String> columns = get(client, base + "/fq/collector/columns?table=author");
            String metrics = get(client, base + "/metrics").body();

            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.body()).contains("\"name\":\"before\"", "\"note\":\"n\"");
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(badName.statusCode()).isEqualTo(400);
            assertThat(badJson.statusCode()).isEqualTo(400);
            assertThat(marks).contains("\"before\"", "\"after\"");
            assertThat(compare.statusCode()).isEqualTo(200);
            assertThat(compare.body()).contains("\"statements\"", "\"endpoints\"", "\"totals\"", "\"status\":\"gone\"", "no collection cycle");
            assertThat(unknown.statusCode()).isEqualTo(400);
            assertThat(columns.statusCode()).isEqualTo(200);
            assertThat(metrics).contains("fq_collector_cycle_duration_seconds{target=\"it\"}", "fq_collector_column_stats");
        } finally {
            http.stop();
        }
    }

    static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> authorLookup() throws Exception {
        List<Map<String, Object>> top = store.topStatements("it", Instant.EPOCH, 50, Store.Order.CALLS);
        return top.stream().filter(r -> String.valueOf(r.get("sql")).contains("FROM author WHERE id = $1"))
                .findFirst().orElseThrow(() -> new AssertionError("author lookup not collected: " + top));
    }
}
