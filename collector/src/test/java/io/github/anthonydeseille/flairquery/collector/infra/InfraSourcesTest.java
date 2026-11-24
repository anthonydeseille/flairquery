package io.github.anthonydeseille.flairquery.collector.infra;

import com.sun.net.httpserver.HttpServer;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;
import io.github.anthonydeseille.flairquery.collector.store.Store.StoredSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6 signals: Kubernetes pods and cAdvisor through a fake API server, quantities, pg_settings units, summary. */
class InfraSourcesTest {

    static final String PODS = """
            {"items": [{
              "metadata": {"name": "demo-app-5d4f8b7c9-x2k4p", "namespace": "fq-dev",
                           "labels": {"pod-template-hash": "5d4f8b7c9"},
                           "ownerReferences": [{"kind": "ReplicaSet", "name": "demo-app-5d4f8b7c9"}]},
              "spec": {"nodeName": "flairquery-control-plane", "containers": [{
                "name": "demo-app",
                "ports": [{"name": "http", "containerPort": 8080}, {"name": "fq-metrics", "containerPort": 9464}],
                "resources": {"requests": {"cpu": "25m", "memory": "64Mi"}, "limits": {"cpu": "500m", "memory": "768Mi"}}}]},
              "status": {"phase": "Running", "podIP": "10.244.0.12", "qosClass": "Burstable",
                "conditions": [{"type": "Ready", "status": "True", "lastTransitionTime": "2026-11-24T10:00:52Z"}],
                "containerStatuses": [{"name": "demo-app", "ready": true, "restartCount": 2,
                  "containerID": "containerd://abc",
                  "state": {"running": {"startedAt": "2026-11-24T10:00:05Z"}},
                  "lastState": {"terminated": {"reason": "OOMKilled", "exitCode": 137, "finishedAt": "2026-11-24T10:00:01Z"}}}]}
            }, {
              "metadata": {"name": "postgres-0", "namespace": "fq-dev",
                           "ownerReferences": [{"kind": "StatefulSet", "name": "postgres"}]},
              "spec": {"nodeName": "flairquery-control-plane", "containers": [{"name": "postgres",
                "resources": {"requests": {"cpu": "0.1", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "1Gi"}}}]},
              "status": {"phase": "Running", "podIP": "10.244.0.9",
                "containerStatuses": [{"name": "postgres", "ready": true, "restartCount": 0,
                  "state": {"running": {"startedAt": "2026-11-24T09:00:00Z"}}}]}
            }]}
            """;

    static final String CADVISOR = """
            # HELP container_cpu_usage_seconds_total Cumulative cpu time consumed in seconds.
            # TYPE container_cpu_usage_seconds_total counter
            container_cpu_usage_seconds_total{container="demo-app",cpu="total",id="/kubepods/x",image="demo",name="abc",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 123.5 1732442400000
            container_cpu_usage_seconds_total{container="",cpu="total",id="/kubepods/pod",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 999
            container_cpu_usage_seconds_total{container="POD",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 1
            container_cpu_usage_seconds_total{container="other",namespace="kube-system",pod="coredns-1"} 50
            container_cpu_cfs_periods_total{container="demo-app",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 1000
            container_cpu_cfs_throttled_periods_total{container="demo-app",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 400
            container_cpu_cfs_throttled_seconds_total{container="demo-app",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 31.2
            container_memory_working_set_bytes{container="demo-app",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 6.5e+08
            container_memory_rss{container="demo-app",namespace="fq-dev",pod="demo-app-5d4f8b7c9-x2k4p"} 6.1e+08
            container_memory_working_set_bytes{container="postgres",namespace="fq-dev",pod="postgres-0"} 2.0e+08
            """;

    HttpServer api;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (api != null) api.stop(0);
    }

    KubeClient fakeApi() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            requests.add(ex.getRequestURI().toString() + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            String body = path.equals("/api/v1/namespaces/fq-dev/pods") ? PODS
                    : path.equals("/api/v1/nodes/flairquery-control-plane/proxy/metrics/cadvisor") ? CADVISOR : null;
            byte[] b = (body == null ? "{\"kind\":\"Status\",\"code\":404}" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        api.start();
        return new KubeClient(URI.create("http://127.0.0.1:" + api.getAddress().getPort()), () -> "sa-token", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void podsResourcesTerminationsAndCadvisorUsage() throws Exception {
        KubernetesSource source = new KubernetesSource(fakeApi(), "fq-dev", "app.kubernetes.io/part-of=flairquery",
                true, "fq-metrics");
        KubernetesSource.Reading r = source.read();

        assertThat(requests).first().asString()
                .isEqualTo("/api/v1/namespaces/fq-dev/pods?labelSelector=app.kubernetes.io%2Fpart-of%3Dflairquery Bearer sa-token");
        assertThat(r.containers()).extracting(InfraSample::subject)
                .containsExactly("fq-dev/demo-app-5d4f8b7c9-x2k4p/demo-app", "fq-dev/postgres-0/postgres");
        Map<String, Object> app = r.containers().get(0).payload();
        assertThat(app).containsEntry("workload", "Deployment/demo-app").containsEntry("restarts", 2)
                .containsEntry("startupSeconds", 47.0).containsEntry("containerId", "containerd://abc");
        assertThat((Map<String, Object>) app.get("requests")).containsEntry("cpuMillis", 25L).containsEntry("memoryBytes", 64L << 20);
        assertThat((Map<String, Object>) app.get("limits")).containsEntry("cpuMillis", 500L).containsEntry("memoryBytes", 768L << 20);
        assertThat((Map<String, Object>) app.get("lastTermination")).containsEntry("reason", "OOMKilled").containsEntry("exitCode", 137);
        assertThat((Map<String, Object>) app.get("usage")).containsEntry("cpuUsageSeconds", 123.5)
                .containsEntry("cfsPeriods", 1000.0).containsEntry("cfsThrottledPeriods", 400.0)
                .containsEntry("memoryWorkingSetBytes", 6.5e8).containsEntry("memoryRssBytes", 6.1e8);
        Map<String, Object> pg = r.containers().get(1).payload();
        assertThat(pg).containsEntry("workload", "StatefulSet/postgres");
        assertThat((Map<String, Object>) pg.get("requests")).containsEntry("cpuMillis", 100L);

        assertThat(r.agents()).containsExactly(new KubernetesSource.Agent("fq-dev", "demo-app-5d4f8b7c9-x2k4p",
                "Deployment/demo-app", "http://10.244.0.12:9464"));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void cadvisorFailureKeepsThePods() throws Exception {
        KubernetesSource source = new KubernetesSource(fakeApi(), "fq-dev", null, true, "fq-metrics");
        api.removeContext("/");
        api.createContext("/", ex -> {
            byte[] b = (ex.getRequestURI().getPath().endsWith("/pods") ? PODS : "forbidden").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().endsWith("/pods") ? 200 : 403, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        KubernetesSource.Reading r = source.read();
        assertThat(r.containers()).hasSize(2).allSatisfy(c -> assertThat(c.payload()).doesNotContainKey("usage"));
        assertThat(r.warnings()).singleElement().asString().contains("HTTP 403");
    }

    @Test
    void cadvisorKeepsOnlyWatchedContainers() {
        var m = Cadvisor.parse(CADVISOR, Set.of("fq-dev/demo-app-5d4f8b7c9-x2k4p"));
        assertThat(m).containsOnlyKeys(new Cadvisor.Key("fq-dev", "demo-app-5d4f8b7c9-x2k4p", "demo-app"));
        assertThat(Cadvisor.labels("a=\"x\\\"y\",b=\"\"")).containsEntry("a", "x\"y").containsEntry("b", "");
    }

    @Test
    void quantities() {
        assertThat(Quantity.millicores("25m")).isEqualTo(25);
        assertThat(Quantity.millicores("0.5")).isEqualTo(500);
        assertThat(Quantity.millicores("2")).isEqualTo(2000);
        assertThat(Quantity.millicores("100u")).isEqualTo(1);   // rounded up
        assertThat(Quantity.bytes("64Mi")).isEqualTo(64L << 20);
        assertThat(Quantity.bytes("1Gi")).isEqualTo(1L << 30);
        assertThat(Quantity.bytes("500M")).isEqualTo(500_000_000L);
        assertThat(Quantity.bytes("1e3")).isEqualTo(1000);
        assertThat(Quantity.bytes(null)).isNull();
    }

    @Test
    void pgSettingUnits() {
        assertThat(PostgresSource.settingBytes("4096", "8kB")).isEqualTo(32L << 20);   // shared_buffers = 32MB
        assertThat(PostgresSource.settingBytes("64", "kB")).isEqualTo(64L << 10);      // work_mem = 64kB
        assertThat(PostgresSource.settingBytes("64", "MB")).isEqualTo(64L << 20);
        assertThat(PostgresSource.settingBytes("-1", "kB")).isNull();                 // temp_file_limit unlimited
        assertThat(PostgresSource.settingBytes("50", null)).isNull();                 // max_connections
        assertThat(PostgresSource.settingBytes("4", "s")).isNull();
    }

    @Test
    void jvmPayloadFromAgent() throws Exception {
        InfraSample s = JvmSource.parse("{\"schemaVersion\":1,\"app\":\"demo-app\",\"jvm\":{\"heap\":{\"usedBytes\":10}}}",
                "fq-dev/p", "Deployment/demo-app");
        assertThat(s.kind()).isEqualTo(InfraSample.JVM);
        assertThat(s.payload()).containsEntry("app", "demo-app").containsEntry("workload", "Deployment/demo-app")
                .containsKey("heap");
    }

    // ---------------------------------------------------------------- summary

    static StoredSample container(int sec, String pod, String id, double cpu, double periods, double throttled, double ws) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("cpuUsageSeconds", cpu);
        usage.put("cfsPeriods", periods);
        usage.put("cfsThrottledPeriods", throttled);
        usage.put("memoryWorkingSetBytes", ws);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("namespace", "fq-dev");
        p.put("pod", pod);
        p.put("workload", "Deployment/demo-app");
        p.put("container", "demo-app");
        p.put("containerId", id);
        p.put("restarts", id.equals("c2") ? 1 : 0);
        p.put("requests", Map.of("cpuMillis", 25, "memoryBytes", 64L << 20));
        p.put("limits", Map.of("cpuMillis", 500, "memoryBytes", 768L << 20));
        p.put("startupSeconds", 47.0);
        p.put("startedAt", pod.equals("a") ? "2026-11-24T09:59:00Z" : "2026-11-23T08:00:00Z");   // b: before the window
        p.put("usage", usage);
        if (id.equals("c2")) p.put("lastTermination", Map.of("reason", "OOMKilled", "exitCode", 137));
        return new StoredSample(Instant.parse("2026-11-24T10:00:00Z").plusSeconds(sec), "container",
                "fq-dev/" + pod + "/demo-app", p);
    }

    @Test
    @SuppressWarnings("unchecked")
    void containerSummaryRatesThrottlingAndRestarts() {
        List<StoredSample> s = new ArrayList<>(List.of(
                container(0, "a", "c1", 10, 100, 10, 500e6),
                container(30, "a", "c1", 25, 400, 160, 600e6),      // 0.5 core, 150/300 throttled
                container(60, "a", "c2", 1, 10, 0, 200e6),          // restarted: interval skipped
                container(90, "a", "c2", 4, 310, 30, 300e6),        // 0.1 core, 30/300 throttled
                container(0, "b", "x1", 0, 0, 0, 100e6),
                container(30, "b", "x1", 3, 300, 0, 110e6)));       // 0.1 core
        s.add(container(5, "old", "o1", 0, 0, 0, 90e6));              // replaced during the window
        Map<String, Object> c = InfraSummary.containers(s, Instant.parse("2026-11-24T09:45:00Z")).get(0);

        assertThat(c).containsEntry("workload", "Deployment/demo-app").containsEntry("replicas", 1)
                .containsEntry("pods", List.of("a", "b", "old"))
                .containsEntry("restarts", 1).containsEntry("oomKilled", 1).containsEntry("startupSecondsMax", 47.0);
        Map<String, Object> cpu = (Map<String, Object>) c.get("cpu");
        assertThat(cpu).containsEntry("rateSamples", 3).containsEntry("usageMaxMillis", 500L)
                .containsEntry("usageP50Millis", 100L).containsEntry("throttledPeriods", 180L)
                .containsEntry("cfsPeriods", 900L).containsEntry("throttledRatio", 0.2);
        assertThat((Map<String, Object>) c.get("memory")).containsEntry("workingSetMaxBytes", 600_000_000L);
    }

    static StoredSample jvm(int sec, long uptime, long gcMs, long heapUsed, long afterGc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("app", "demo-app");
        p.put("workload", "Deployment/demo-app");
        p.put("runtime", Map.of("uptimeMs", uptime, "containerMemoryBytes", 768L << 20, "rssBytes", 700L << 20));
        p.put("flags", Map.of("MaxRAMPercentage", Map.of("value", "80.000000", "origin", "ENVIRON_VAR")));
        p.put("heap", Map.of("usedBytes", heapUsed, "maxBytes", 614L << 20));
        p.put("nonHeap", Map.of("usedBytes", 150L << 20));
        p.put("pools", List.of(Map.of("name", "G1 Old Gen", "type", "HEAP", "usedBytes", heapUsed, "usedAfterGcBytes", afterGc),
                Map.of("name", "Metaspace", "type", "NON_HEAP", "usedBytes", 110L << 20)));
        p.put("gc", List.of(Map.of("name", "G1 Young Generation", "count", 10, "timeMs", gcMs)));
        p.put("threads", Map.of("live", 40, "peak", 52));
        p.put("hikari", List.of(Map.of("pool", "HikariPool-1", "maximumPoolSize", 10, "peakActive", 10, "peakWaiting", 3)));
        return new StoredSample(Instant.parse("2026-11-24T10:00:00Z").plusSeconds(sec), "jvm", "fq-dev/a", p);
    }

    @Test
    void jvmSummaryLiveSetAndGcRatio() {
        Map<String, Object> j = InfraSummary.jvms(List.of(
                jvm(0, 100_000, 1_000, 300L << 20, 120L << 20),
                jvm(30, 130_000, 4_000, 500L << 20, 180L << 20),   // 3 s of GC in 30 s
                jvm(60, 5_000, 0, 100L << 20, 0))).get(0);          // restarted: interval skipped
        assertThat(j).containsEntry("maxRamPercentage", "80.000000").containsEntry("heapMaxBytes", 614L << 20)
                .containsEntry("heapUsedMaxBytes", 500L << 20).containsEntry("liveSetMaxBytes", 180L << 20)
                .containsEntry("metaspaceUsedMaxBytes", 110L << 20).containsEntry("gcTimeRatio", 0.1)
                .containsEntry("threadsPeak", 52).containsEntry("containerMemoryBytes", 768L << 20);
    }

    static StoredSample pg(int sec, long temp, long hit, long read, long conns) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("settings", Map.of("max_connections", Map.of("setting", "50"),
                "superuser_reserved_connections", Map.of("setting", "3"),
                "work_mem", Map.of("setting", "64", "unit", "kB", "bytes", 65536)));
        p.put("database", Map.of("datname", "flairquery", "tempFiles", temp / 1000, "tempBytes", temp,
                "blksHit", hit, "blksRead", read, "deadlocks", 0, "statsReset", "2026-11-24T09:00:00Z"));
        p.put("clientConnections", conns);
        p.put("connections", List.of(Map.of("role", "flairquery", "application", "PostgreSQL JDBC Driver", "state", "idle", "count", conns)));
        return new StoredSample(Instant.parse("2026-11-24T10:00:00Z").plusSeconds(sec), "postgres", "postgres", p);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postgresSummaryTempBytesAndConnectionPeak() {
        Map<String, Object> m = InfraSummary.postgres(List.of(pg(0, 1_000, 900, 100, 12), pg(30, 81_000, 1_800, 200, 31),
                pg(60, 81_000, 2_700, 300, 20)));
        Map<String, Object> db = (Map<String, Object>) m.get("database");
        assertThat(db).containsEntry("tempBytes", 80_000L).containsEntry("tempFiles", 80L).containsEntry("cacheHitRatio", 0.9);
        Map<String, Object> c = (Map<String, Object>) m.get("connections");
        assertThat(c).containsEntry("maxConnections", 50L).containsEntry("reserved", 3L).containsEntry("clientPeak", 31L)
                .containsEntry("current", 20L);
        assertThat((Map<String, Long>) c.get("peakByApplication")).containsEntry("PostgreSQL JDBC Driver", 31L);
    }

    @Test
    void percentileNearestRank() {
        assertThat(InfraSummary.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5)).isEqualTo(2.0);
        assertThat(InfraSummary.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.95)).isEqualTo(4.0);
        assertThat(InfraSummary.percentile(List.of(), 0.95)).isNull();
    }
}
