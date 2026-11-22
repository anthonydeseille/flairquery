package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.context.RequestContextHolder;
import io.github.anthonydeseille.flairquery.agent.core.FqRuntime;
import io.github.anthonydeseille.flairquery.agent.http.FqHttpServer;
import io.github.anthonydeseille.flairquery.agent.metrics.PrometheusWriter;
import io.github.anthonydeseille.flairquery.fixture.FakeHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Measurement and N+1 detection driven through the runtime hooks (no bytecode instrumentation). */
class MeasurementTest {

    private final FqRuntime runtime = new FqRuntime(FqConfig.fromEnv(Map.of(
            "FQ_APP_NAME", "demo", "FQ_NPLUSONE_THRESHOLD", "5")));

    @AfterEach
    void clean() {
        RequestContextHolder.clear();
    }

    private void request(String method, String pattern, Map<String, Integer> sqlCounts) {
        var req = FakeHttp.request(method, "/raw");
        runtime.requestEnter(req);
        req.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern", pattern);
        sqlCounts.forEach((sql, n) -> {
            for (int i = 0; i < n; i++) {
                String tagged = runtime.tagSql(sql.replace("?", String.valueOf(i)));
                long start = runtime.queryStart();
                runtime.queryEnd(new Object(), tagged, start, null);
            }
        });
        runtime.requestExit(req, FakeHttp.response(), null);
    }

    @Test
    void detectsNPlusOneAboveThresholdOnly() {
        request("GET", "/authors", Map.of(
                "select * from author", 1,
                "select * from book where author_id = ?", 20));
        request("GET", "/publishers/{id}", Map.of(
                "select * from publisher where id = ?", 1));
        request("GET", "/books/borderline", Map.of(
                "select * from book where id = ?", 5)); // == threshold: not N+1

        List<Map<String, Object>> n1 = runtime.registry().nPlusOne();
        assertThat(n1).hasSize(1);
        assertThat(n1.get(0))
                .containsEntry("endpoint", "GET /authors")
                .containsEntry("sql", "select * from book where author_id = ?")
                .containsEntry("occurrences", 1L)
                .containsEntry("maxQueriesPerRequest", 20L);
    }

    @Test
    void topIsOrderedByTotalTimeAndCarriesEndpointAndCaller() {
        request("GET", "/authors", Map.of("select * from book where author_id = ?", 30));
        request("GET", "/authors", Map.of("select * from book where author_id = ?", 30));

        Map<String, Object> top = runtime.registry().top(10).get(0);
        assertThat(top).containsEntry("endpoint", "GET /authors")
                .containsEntry("sql", "select * from book where author_id = ?")
                .containsEntry("calls", 60L);
        assertThat(top).containsKey("caller").containsKey("totalTimeMs").containsKey("maxTimeMs");
    }

    @Test
    void backgroundQueriesAreAttributedToNoneEndpoint() {
        String tagged = runtime.tagSql("select 1");
        runtime.queryEnd(new Object(), tagged, runtime.queryStart(), null);

        assertThat(runtime.registry().top(5).get(0)).containsEntry("endpoint", "none");
    }

    @Test
    void nestedExecutionsAreMeasuredOnce() {
        var req = FakeHttp.request("GET", "/x");
        runtime.requestEnter(req);
        String sql = runtime.tagSql("select 1");
        long outer = runtime.queryStart();   // pool proxy
        long inner = runtime.queryStart();   // driver
        assertThat(inner).isEqualTo(-1L);
        runtime.queryEnd(new Object(), sql, inner, null);
        runtime.queryEnd(new Object(), sql, outer, null);
        runtime.requestExit(req, FakeHttp.response(), null);

        assertThat(runtime.registry().endpoints().get(0)).containsEntry("queries", 1L);
    }

    @Test
    void prometheusExposition() {
        request("GET", "/authors", Map.of("select * from book where author_id = ?", 10));

        String text = PrometheusWriter.write(runtime.registry());

        assertThat(text)
                .contains("# TYPE fq_queries_count counter")
                .contains("fq_queries_count{app=\"demo\",endpoint=\"GET /authors\"} 10")
                .contains("# TYPE fq_queries_time_seconds counter")
                .contains("fq_nplusone{app=\"demo\",endpoint=\"GET /authors\"} 1")
                .contains("fq_http_requests_count{app=\"demo\",endpoint=\"GET /authors\"} 1");
    }

    @Test
    void httpEndpoints() throws Exception {
        request("GET", "/authors", Map.of("select * from book where author_id = ?", 10));
        FqHttpServer server = new FqHttpServer(runtime.registry());
        assertThat(server.start(0)).isTrue();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            HttpResponse<String> top = client.send(HttpRequest.newBuilder(URI.create(base + "/fq/top?limit=5")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> n1 = client.send(HttpRequest.newBuilder(URI.create(base + "/fq/nplusone")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "/fq/top"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(top.statusCode()).isEqualTo(200);
            assertThat(top.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
            assertThat(top.body()).startsWith("{\"schemaVersion\":1,\"app\":\"demo\"")
                    .contains("\"endpoint\":\"GET /authors\"", "\"calls\":10");
            assertThat(n1.body()).contains("\"threshold\":5", "\"maxQueriesPerRequest\":10");
            assertThat(metrics.body()).contains("fq_queries_count{");
            assertThat(post.statusCode()).isEqualTo(405);
        } finally {
            server.stop();
        }
    }

    @Test
    void busyPortDoesNotFail() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            FqHttpServer server = new FqHttpServer(runtime.registry());
            assertThat(server.start(socket.getLocalPort())).isFalse();
        }
    }

    @Test
    void unsampledRequestsAreNeitherTaggedNorMeasured() {
        FqRuntime none = new FqRuntime(FqConfig.fromEnv(Map.of("FQ_SAMPLING_RATE", "0")));
        var req = FakeHttp.request("GET", "/x");
        none.requestEnter(req);
        String sql = none.tagSql("select 1");
        none.queryEnd(new Object(), sql, none.queryStart(), null);
        none.requestExit(req, FakeHttp.response(), null);

        assertThat(sql).isEqualTo("select 1");
        assertThat(none.registry().endpoints()).isEmpty();
    }
}
