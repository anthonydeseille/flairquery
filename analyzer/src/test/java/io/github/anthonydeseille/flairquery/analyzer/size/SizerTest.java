package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sizing rules on a synthetic {@code /fq/collector/infra} summary whose figures are those measured on kind (fq-dev,
 * one load round, 2026-11-24): demo app throttled, 595 MiB heap in 768 MiB, 714 MB of temporary files, cache hit
 * ratio 0.38; the collector (witness) only throttled during its startup.
 */
class SizerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long MI = 1024L * 1024;

    static ObjectNode container(String workload, String name, int replicas, long cpuReq, long memReq, Long cpuLim, long memLim,
                                long p50, long p95, long max, int samples, long periods, double throttled,
                                double startupThrottled, double startupSeconds, long wsMax) {
        ObjectNode c = JSON.createObjectNode();
        c.put("namespace", "fq-dev").put("workload", workload).put("container", name).put("replicas", replicas);
        c.putObject("requests").put("cpuMillis", cpuReq).put("memoryBytes", memReq);
        ObjectNode lim = c.putObject("limits");
        if (cpuLim != null) lim.put("cpuMillis", cpuLim);
        lim.put("memoryBytes", memLim);
        c.putObject("memory").put("workingSetMaxBytes", wsMax).put("workingSetP95Bytes", wsMax);
        c.putObject("cpu").put("usageP50Millis", p50).put("usageP95Millis", p95).put("usageMaxMillis", max)
                .put("rateSamples", samples).put("cfsPeriods", periods).put("throttledRatio", throttled)
                .put("throttledSeconds", 10.0);
        c.putObject("startup").put("throttledRatio", startupThrottled).put("cfsPeriods", 500);
        c.put("startupSecondsMax", startupSeconds).put("restarts", 0).put("oomKilled", 0).put("qosClass", "Burstable");
        return c;
    }

    static ObjectNode jvm(String workload, String pod, int pool) {
        ObjectNode j = JSON.createObjectNode();
        j.put("subject", "fq-dev/" + pod).put("workload", workload).put("containerMemoryBytes", 768 * MI)
                .put("maxRamPercentage", "80.0").put("maxRamPercentageOrigin", "ENVIRON_VAR")
                .put("heapMaxBytes", 595 * MI).put("heapUsedMaxBytes", 129 * MI).put("liveSetMaxBytes", 76 * MI)
                .put("nonHeapUsedMaxBytes", 131 * MI).put("rssMaxBytes", 377 * MI).put("threadsPeak", 29)
                .put("gcTimeRatio", 0.012);
        j.putArray("hikari").addObject().put("pool", "HikariPool-1").put("maximumPoolSize", pool)
                .put("peakActive", pool).put("peakWaiting", 0);
        return j;
    }

    /** fq-dev after one load round; {@code demoReplicas} 3 and {@code pool} 20 for the pool-overflow overlay. */
    static ObjectNode infra(int demoReplicas, int pool) {
        ObjectNode i = JSON.createObjectNode();
        i.put("target", "postgres");
        i.putObject("sources").put("kubernetes", 1).put("jvmRead", demoReplicas).put("jvmFailed", 0);
        var cs = i.putArray("containers");
        cs.add(container("Deployment/demo-app", "demo-app", demoReplicas, 25, 64 * MI, 500L, 768 * MI,
                40, 463, 498, 10, 1200, 0.40, 0.9, 77, 390 * MI));
        cs.add(container("Deployment/fq-collector", "collector", 1, 50, 128 * MI, 500L, 256 * MI,
                20, 45, 60, 10, 1200, 0.02, 0.6, 17, 139 * MI));       // witness: throttled at startup only (17 s)
        cs.add(container("Deployment/fq-store", "postgres", 1, 50, 64 * MI, 500L, 256 * MI,
                26, 54, 54, 10, 400, 0.05, 0.0, 0, 26 * MI));
        cs.add(container("Deployment/postgres", "postgres", 1, 100, 128 * MI, 1000L, 1024 * MI,
                24, 538, 538, 10, 400, 0.0098, 0.0, 0, 225 * MI));   // measured: 0.98 % throttled
        cs.add(container("Job/fq-load", "load", 1, 10, 16 * MI, 100L, 64 * MI,
                1, 900, 900, 10, 400, 0.9, 0.0, 0, 9 * MI));            // a Job: never sized
        var js = i.putArray("jvms");
        for (int r = 0; r < demoReplicas; r++) js.add(jvm("Deployment/demo-app", "demo-app-" + r, pool));
        ObjectNode pg = i.putObject("postgres");
        ObjectNode s = pg.putObject("settings");
        s.putObject("work_mem").put("setting", "64").put("unit", "kB").put("bytes", 65536);
        s.putObject("shared_buffers").put("setting", "4096").put("unit", "8kB").put("bytes", 32 * MI);
        s.putObject("effective_cache_size").put("setting", "524288").put("unit", "8kB").put("bytes", 4096 * MI);
        s.putObject("max_connections").put("setting", "50");
        s.putObject("superuser_reserved_connections").put("setting", "3");
        pg.putObject("database").put("tempFiles", 34).put("tempBytes", 714_453_273L).put("cacheHitRatio", 0.3808);
        ObjectNode conn = pg.putObject("connections");
        conn.put("maxConnections", 50).put("reserved", 3).put("clientPeak", demoReplicas * pool + 1);
        conn.putObject("peakByRole").put("flairquery", demoReplicas * pool).put("fq_reader", 1);
        return i;
    }

    static JsonNode tempQueries() {
        ObjectNode q = JSON.createObjectNode();
        q.putArray("queries").addObject().put("sql", "select r.id, r.comment from review r order by r.created_at desc, r.comment offset $1 rows fetch first $2 rows only")
                .put("endpoint", "GET /reviews/ranking").put("calls", 5).put("tempBlksWritten", 12_800);   // 20 MiB per call
        return q;
    }

    static SizingFinding only(List<SizingFinding> fs, Sizer.Rule rule, String targetPrefix) {
        List<SizingFinding> m = fs.stream().filter(f -> f.rule().equals(rule.name()) && f.target().startsWith(targetPrefix)).toList();
        assertThat(m).as(rule + " on " + targetPrefix).hasSize(1);
        return m.get(0);
    }

    @Test
    void devTrapsDetectedWitnessCleanAndRecommendationsComputed() {
        List<SizingFinding> fs = new Sizer("30m", null).size(infra(1, 10), tempQueries());

        SizingFinding i1 = only(fs, Sizer.Rule.JVM_HEAP_TOO_LARGE, InfraScorecard.DEMO);
        // native = 131 + 29 + 64 = 224 MiB, x 1.25 = 280 MiB; heap = 768 - 280 = 488 MiB = 63.5 % -> 60 %
        assertThat(i1.recommended()).containsEntry("MaxRAMPercentage", 60).containsEntry("memoryLimit", "768Mi");
        assertThat(i1.current()).containsEntry("MaxRAMPercentage", "80.0");

        SizingFinding i2 = only(fs, Sizer.Rule.REQUESTS_BELOW_USAGE, InfraScorecard.DEMO);
        // 390 x 1.15 = 448.5 MiB -> 480Mi (32Mi steps)
        assertThat(i2.recommended()).containsEntry("cpuRequest", "475m").containsEntry("memoryRequest", "480Mi");
        assertThat(i2.notes()).anySatisfy(n -> assertThat(n).contains("capped by a throttling limit"));

        SizingFinding i3 = only(fs, Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO);
        assertThat(i3.recommended()).containsEntry("cpuLimit", "1");
        assertThat(i3.title()).contains("40 %").contains("77 s startup");

        SizingFinding i4 = only(fs, Sizer.Rule.WORK_MEM_SPILLS, "postgres:Deployment/postgres");
        // spill 100 MiB/5 calls = 20 MiB x 2 -> 64MB needed; budget (1024 - 256 - 64) / (11 x 2) = 32 MiB -> 32MB
        assertThat(i4.recommended()).containsEntry("work_mem", "32MB")
                .containsEntry("sql", "ALTER ROLE flairquery SET work_mem = '32MB'").containsEntry("restart", false);
        assertThat(i4.notes()).anySatisfy(n -> assertThat(n).contains("trap 7"));
        assertThat(i4.evidence()).containsEntry("topStatementEndpoint", "GET /reviews/ranking");

        SizingFinding i5 = only(fs, Sizer.Rule.SHARED_BUFFERS_SMALL, "postgres:Deployment/postgres");
        assertThat(i5.recommended()).containsEntry("shared_buffers", "256MB").containsEntry("effective_cache_size", "768MB")
                .containsEntry("restart", true);

        assertThat(fs).noneMatch(f -> f.rule().equals(Sizer.Rule.POOL_EXCEEDS_CONNECTIONS.name()));   // 10 x 1 fits
        assertThat(fs).noneMatch(f -> f.target().startsWith("Job/"));
        assertThat(fs).noneMatch(f -> f.target().startsWith(InfraScorecard.WITNESS));

        List<InfraScorecard.Row> rows = InfraScorecard.of(fs, infra(1, 10));
        assertThat(rows).extracting(InfraScorecard.Row::trap, InfraScorecard.Row::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("I1", InfraScorecard.Status.DETECTED),
                org.assertj.core.groups.Tuple.tuple("I2", InfraScorecard.Status.DETECTED),
                org.assertj.core.groups.Tuple.tuple("I3", InfraScorecard.Status.DETECTED),
                org.assertj.core.groups.Tuple.tuple("I4", InfraScorecard.Status.DETECTED),
                org.assertj.core.groups.Tuple.tuple("I5", InfraScorecard.Status.DETECTED),
                org.assertj.core.groups.Tuple.tuple("I6", InfraScorecard.Status.NOT_DEPLOYED),
                org.assertj.core.groups.Tuple.tuple("witness", InfraScorecard.Status.CLEAN));
        assertThat(InfraScorecard.markdown(rows)).contains("phase 6: 5/5 infrastructure traps detected, witness clean");
    }

    @Test
    void poolOverflowOverlay() {
        List<SizingFinding> fs = new Sizer("30m", null).size(infra(3, 20), tempQueries());
        SizingFinding i6 = only(fs, Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:");
        // 50 - 3 reserved - 1 collector - 3 admin = 43 for 3 replicas -> 14 each
        assertThat(i6.evidence()).containsEntry("poolConnectionsTotal", 60L).containsEntry("availableForPools", 43L);
        assertThat(i6.recommended()).containsEntry("maximumPoolSize", 14L).containsEntry("env", "HIKARI_MAX_POOL=14");
        assertThat(i6.severity()).isEqualTo("HIGH");
        assertThat(InfraScorecard.of(fs, infra(3, 20))).filteredOn(r -> r.trap().equals("I6"))
                .singleElement().extracting(InfraScorecard.Row::status).isEqualTo(InfraScorecard.Status.DETECTED);
    }

    /** Measured on kind (profile S, 40 load rounds): no startup in the window. */
    @Test
    void sustainedThrottlingIsAFindingBurstThrottlingIsNot() {
        ObjectNode i = infra(1, 10);
        ObjectNode demo = (ObjectNode) i.path("containers").get(0);
        ((ObjectNode) demo.path("cpu")).put("throttledRatio", 0.1378).put("usageP95Millis", 426);
        ((ObjectNode) demo.path("startup")).put("throttledRatio", 0.0);
        demo.put("startupSecondsMax", (Double) null);
        ObjectNode collector = (ObjectNode) i.path("containers").get(1);
        ((ObjectNode) collector.path("cpu")).put("throttledRatio", 0.2272).put("usageP95Millis", 80);   // bursts
        List<SizingFinding> fs = new Sizer("10m", null).size(i, tempQueries());

        SizingFinding i3 = only(fs, Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO);
        assertThat(i3.title()).contains("14 %").doesNotContain("startup");
        assertThat(i3.evidence()).containsEntry("cpuUsageP95OfLimit", "85 %");
        assertThat(fs).noneMatch(f -> f.target().startsWith(InfraScorecard.WITNESS));
    }

    @Test
    void sharedBuffersNotTriggeredWhenTheDataFit() {
        ObjectNode i = infra(1, 10);
        ((ObjectNode) i.path("postgres").path("database")).put("cacheHitRatio", 0.985);   // profile S: 49 MB of data
        List<SizingFinding> fs = new Sizer("30m", null).size(i, tempQueries());
        assertThat(fs).noneMatch(f -> f.rule().equals(Sizer.Rule.SHARED_BUFFERS_SMALL.name()));
        List<InfraScorecard.Row> rows = InfraScorecard.of(fs, i);
        assertThat(rows).filteredOn(r -> r.trap().equals("I5")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(InfraScorecard.Status.NOT_TRIGGERED))
                .satisfies(r -> assertThat(r.detail()).contains("98.5 %"));
        assertThat(InfraScorecard.markdown(rows)).contains("phase 6: 4/4 infrastructure traps detected");
    }

    /** Measured on kind: the pool-overflow rollout stuck with 4 pods, the old one (pool 10) and a crashing new one. */
    @Test
    void poolsOfARolloutInProgressAreCountedPodByPod() {
        ObjectNode i = infra(3, 20);
        ObjectNode demo = (ObjectNode) i.path("containers").get(0);
        demo.put("replicas", 4);
        var cur = demo.putArray("currentPods");
        for (String p : List.of("demo-app-0", "demo-app-1", "demo-app-2", "demo-app-old")) cur.add(p);
        ((ObjectNode) i.path("jvms").get(2)).put("subject", "fq-dev/demo-app-old")
                .withArray("hikari").removeAll().addObject().put("pool", "HikariPool-1").put("maximumPoolSize", 10);
        // demo-app-2 crashing: never read
        SizingFinding i6 = only(new Sizer("10m", null).size(i, tempQueries()), Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:");
        assertThat(i6.evidence()).containsEntry("poolConnectionsTotal", 70L).containsEntry("podsNotRead", 1);   // 20 + 20 + 10 + 20
        assertThat(i6.notes()).anySatisfy(n -> assertThat(n).contains("rollout was in progress"));
    }

    @Test
    void tooFewSamplesStaySilentAndHealthyContainersAreNotFlagged() {
        ObjectNode i = infra(1, 10);
        ((ObjectNode) i.path("containers").get(0).path("cpu")).put("rateSamples", 2);
        List<SizingFinding> fs = new Sizer("30m", null).size(i, JSON.createObjectNode());
        assertThat(fs).noneMatch(f -> f.rule().equals(Sizer.Rule.REQUESTS_BELOW_USAGE.name())
                && f.target().equals(InfraScorecard.DEMO));
        // fq-store: usage within its requests, no throttling
        assertThat(fs).noneMatch(f -> f.target().startsWith("Deployment/fq-store"));
    }

    @Test
    void heapThatCannotFitTheLiveSetRaisesTheLimit() {
        ObjectNode i = infra(1, 10);
        ((ObjectNode) i.path("jvms").get(0)).put("liveSetMaxBytes", 400 * MI);
        SizingFinding i1 = only(new Sizer("30m", null).size(i, JSON.createObjectNode()),
                Sizer.Rule.JVM_HEAP_TOO_LARGE, InfraScorecard.DEMO);
        // 2 x 400 + 280 = 1080 MiB -> 1088Mi (64Mi steps); heap (1088 - 280) / 1088 = 74 % -> 70 %
        assertThat(i1.recommended()).containsEntry("memoryLimit", "1088Mi").containsEntry("MaxRAMPercentage", 70);
    }

    @Test
    void commandWritesFindingsAndScorecard(@TempDir Path out) throws Exception {
        SizeCommand.write(infra(1, 10), tempQueries(), "30m", null, out);
        JsonNode doc = JSON.readTree(out.resolve("findings.json").toFile());
        assertThat(doc.path("findings")).hasSize(6);   // I1-I5 + postgres requests (secondary, true)
        assertThat(Files.readString(out.resolve("scorecard.md"))).contains("| I6 |", "NOT_DEPLOYED", "witness clean");
        assertThat(out.resolve("infra.json")).exists();
    }

    @Test
    void units() {
        assertThat(Units.cpu(1000)).isEqualTo("1");
        assertThat(Units.cpu(475)).isEqualTo("475m");
        assertThat(Units.memory(1024 * MI)).isEqualTo("1Gi");
        assertThat(Units.pg(32 * MI)).isEqualTo("32MB");
        assertThat(Units.pg(64 * 1024)).isEqualTo("64kB");
        assertThat(Units.ceilPow2Mi(40 * MI)).isEqualTo(64 * MI);
        assertThat(Units.floorPow2Mi(40 * MI)).isEqualTo(32 * MI);
        assertThat(Sizer.id(Sizer.Rule.CPU_THROTTLED, "Deployment/demo-app/demo-app"))
                .isEqualTo("cpu-throttled-deployment-demo-app-demo-app");
    }
}
