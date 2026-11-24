package io.github.anthonydeseille.flairquery.agent;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.anthonydeseille.flairquery.agent.http.FqHttpServer;
import io.github.anthonydeseille.flairquery.agent.metrics.JvmMetrics;
import io.github.anthonydeseille.flairquery.agent.metrics.PrometheusWriter;
import io.github.anthonydeseille.flairquery.agent.metrics.Registry;
import io.github.anthonydeseille.flairquery.agent.profile.ProfileRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JVM and HikariCP signals for infrastructure sizing (phase 6). */
class JvmMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void snapshotHasMemoryGcThreadsAndFlags() {
        Map<String, Object> s = new JvmMetrics().snapshot();

        Map<String, Object> heap = (Map<String, Object>) s.get("heap");
        assertThat((Long) heap.get("usedBytes")).isPositive();
        assertThat((Long) heap.get("maxBytes")).isPositive();
        assertThat((List<Map<String, Object>>) s.get("pools")).isNotEmpty()
                .anySatisfy(p -> assertThat(p.get("type")).isEqualTo("HEAP"))
                .anySatisfy(p -> assertThat(p.get("name")).isEqualTo("Metaspace"));
        assertThat((List<Map<String, Object>>) s.get("gc")).isNotEmpty()
                .allSatisfy(g -> assertThat(g).containsKeys("name", "count", "timeMs"));
        assertThat((Map<String, Object>) s.get("threads")).containsKeys("live", "peak", "daemon");
        Map<String, Object> flags = (Map<String, Object>) s.get("flags");
        assertThat(flags).containsKeys("MaxRAMPercentage", "MaxHeapSize");
        assertThat((Map<String, Object>) s.get("runtime")).containsKeys("availableProcessors", "uptimeMs");
        assertThat((List<?>) s.get("hikari")).isEmpty();   // no pool registered as MBean yet
    }

    @Test
    @SuppressWarnings("unchecked")
    void hikariPoolsReadFromMBeansWithSampledPeaks() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jvmmetrics;DB_CLOSE_DELAY=-1");
        config.setPoolName("fq-test-pool");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setRegisterMbeans(true);
        JvmMetrics jvm = new JvmMetrics();
        try (HikariDataSource ds = new HikariDataSource(config)) {
            try (Connection a = ds.getConnection(); Connection b = ds.getConnection(); Connection c = ds.getConnection()) {
                jvm.snapshot();   // samples the peak: 3 active
            }
            Map<String, Object> pool = ((List<Map<String, Object>>) jvm.snapshot().get("hikari")).stream()
                    .filter(p -> p.get("pool").equals("fq-test-pool")).findFirst().orElseThrow();
            assertThat(pool).containsEntry("maximumPoolSize", 4).containsEntry("minimumIdle", 1)
                    .containsEntry("active", 0).containsEntry("peakActive", 3).containsEntry("peakWaiting", 0);

            String prom = PrometheusWriter.write(new Registry("demo", 5), jvm.snapshot());
            assertThat(prom).contains("fq_hikari_connections{app=\"demo\",pool=\"fq-test-pool\",state=\"maximumPoolSize\"} 4")
                    .contains("fq_hikari_connections{app=\"demo\",pool=\"fq-test-pool\",state=\"peakActive\"} 3");
        }
    }

    @Test
    void prometheusFamiliesAreContiguous() {
        String prom = PrometheusWriter.write(new Registry("demo", 5), new JvmMetrics().snapshot());
        assertThat(prom).contains("fq_jvm_memory_used_bytes{app=\"demo\",area=\"heap\"}")
                .contains("fq_jvm_memory_used_bytes{app=\"demo\",area=\"nonheap\"}")
                .contains("fq_jvm_threads{app=\"demo\",state=\"peak\"}");
        // each family declared once, its samples right after its TYPE line
        String[] lines = prom.split("\n");
        java.util.Set<String> closed = new java.util.HashSet<>();
        String current = null;
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            String family = line.substring(0, line.indexOf('{'));
            if (!family.equals(current)) {
                assertThat(closed).as("family %s split", family).doesNotContain(family);
                if (current != null) closed.add(current);
                current = family;
            }
        }
    }

    @Test
    void servedOnFqJvm() throws Exception {
        FqHttpServer http = new FqHttpServer(new Registry("demo", 5), new ProfileRegistry(), new JvmMetrics());
        assertThat(http.start(0)).isTrue();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + http.port() + "/fq/jvm")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"app\":\"demo\"").contains("\"jvm\":{").contains("\"MaxRAMPercentage\"")
                    .contains("\"hikari\":[");
        } finally {
            http.stop();
        }
    }
}
