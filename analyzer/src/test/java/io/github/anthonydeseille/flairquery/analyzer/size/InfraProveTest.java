package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6, step 3 without a cluster: patches generated against the rendered pool-overflow overlay (kubectl output,
 * fixture), relocation into the replay namespace, load parsing and the judge on synthetic measurements.
 */
class InfraProveTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long MI = 1024L * 1024;

    static JsonNode rendered() throws Exception {
        try (InputStream in = InfraProveTest.class.getResourceAsStream("/size/pool-overflow-rendered.json")) {
            return JSON.readTree(in);
        }
    }

    static Map<String, JsonNode> deployments() throws Exception {
        List<JsonNode> items = new ArrayList<>();
        rendered().forEach(items::add);
        return Relocation.deployments(items);
    }

    static List<SizingFinding> findings(int replicas, int pool) {
        return new Sizer("30m", null).size(SizerTest.infra(replicas, pool), SizerTest.tempQueries());
    }

    // ---------------------------------------------------------------- patches

    @Test
    void heapPatchRewritesOnlyTheFlagInTheVariableThatSetsIt() throws Exception {
        SizingFinding i1 = SizerTest.only(findings(1, 10), Sizer.Rule.JVM_HEAP_TOO_LARGE, InfraScorecard.DEMO);
        InfraPatches.Patch p = InfraPatches.of(i1, deployments());
        assertThat(p.kind()).isEqualTo("Deployment");
        assertThat(p.name()).isEqualTo("demo-app");
        assertThat(p.fileName()).isEqualTo(i1.id() + ".yaml");
        assertThat(p.yaml()).isEqualTo("""
                # FlairQuery phase 6: JVM_HEAP_TOO_LARGE (jvm-heap-too-large-deployment-demo-app-demo-app)
                # demo-app: JAVA_TOOL_OPTIONS -XX:MaxRAMPercentage=60
                apiVersion: "apps/v1"
                kind: "Deployment"
                metadata:
                  name: "demo-app"
                spec:
                  template:
                    spec:
                      containers:
                        - name: "demo-app"
                          env:
                            - name: "JAVA_TOOL_OPTIONS"
                              value: "-javaagent:/fq/agent.jar -XX:MaxRAMPercentage=60"
                """);
    }

    @Test
    void resourcePatches() throws Exception {
        List<SizingFinding> fs = findings(1, 10);
        InfraPatches.Patch req = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.REQUESTS_BELOW_USAGE, InfraScorecard.DEMO), deployments());
        assertThat(req.yaml()).contains("""
                          resources:
                            requests:
                              cpu: "475m"
                              memory: "480Mi"
                """);
        assertThat(req.summary()).isEqualTo("demo-app/demo-app: requests cpu 25m -> 475m, memory 64Mi -> 480Mi");

        InfraPatches.Patch cpu = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO), deployments());
        assertThat(cpu.yaml()).contains("""
                            limits:
                              cpu: "1"
                """);
        assertThat(cpu.summary()).isEqualTo("demo-app/demo-app: limits cpu 500m -> 1");
    }

    @Test
    void postgresSettingsAreAppendedToItsArguments() throws Exception {
        List<SizingFinding> fs = findings(1, 10);
        InfraPatches.Patch wm = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.WORK_MEM_SPILLS, "postgres:"), deployments());
        assertThat(wm.name()).isEqualTo("postgres");
        assertThat(wm.yaml()).contains("""
                        - name: "postgres"
                          args:
                            - "-c"
                            - "config_file=/etc/postgresql/postgresql.conf"
                            - "-c"
                            - "work_mem=32MB"
                """);
        InfraPatches.Patch sb = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.SHARED_BUFFERS_SMALL, "postgres:"), deployments());
        assertThat(sb.yaml()).contains("\"shared_buffers=256MB\"").contains("\"effective_cache_size=768MB\"");
        assertThat(sb.summary()).isEqualTo("postgres: PostgreSQL shared_buffers=256MB, effective_cache_size=768MB");
    }

    @Test
    void poolPatchTargetsThePooledWorkloadAndTheVariableHoldingTheSize() throws Exception {
        // the rendered overlay sets HIKARI_MAX_POOL=20 (pool-overflow), measured 3 x 20
        SizingFinding i6 = SizerTest.only(findings(3, 20), Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:");
        InfraPatches.Patch p = InfraPatches.of(i6, deployments());
        assertThat(p.name()).isEqualTo("demo-app");
        assertThat(p.yaml()).contains("""
                            - name: "HIKARI_MAX_POOL"
                              value: "14"
                """);
        assertThat(p.summary()).isEqualTo("demo-app: HIKARI_MAX_POOL 20 -> 14 (connections per replica)");
    }

    @Test
    void unsupportedTargetsAreReportedNotGuessed() throws Exception {
        SizingFinding i2 = SizerTest.only(findings(1, 10), Sizer.Rule.REQUESTS_BELOW_USAGE, InfraScorecard.DEMO);
        SizingFinding onStatefulSet = new SizingFinding(i2.id(), i2.rule(), i2.severity(), "StatefulSet/db/db", i2.title(),
                i2.evidence(), i2.current(), i2.recommended(), i2.rationale(), i2.notes());
        assertThatThrownBy(() -> InfraPatches.of(onStatefulSet, deployments()))
                .isInstanceOf(InfraPatches.Unsupported.class).hasMessageContaining("only Deployments");
        SizingFinding otherContainer = new SizingFinding(i2.id(), i2.rule(), i2.severity(), "Deployment/demo-app/sidecar",
                i2.title(), i2.evidence(), i2.current(), i2.recommended(), i2.rationale(), i2.notes());
        assertThatThrownBy(() -> InfraPatches.of(otherContainer, deployments()))
                .isInstanceOf(InfraPatches.Unsupported.class).hasMessageContaining("container not identified");
    }

    @Test
    void kustomizationAppliesThePatchesOnTheOverlay() throws Exception {
        List<SizingFinding> fs = findings(1, 10);
        InfraPatches.Patch a = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO), deployments());
        InfraPatches.Patch b = InfraPatches.of(SizerTest.only(fs, Sizer.Rule.WORK_MEM_SPILLS, "postgres:"), deployments());
        assertThat(InfraPatches.kustomization("../../../../deploy/k8s/overlays/dev", List.of(a, b))).endsWith("""
                apiVersion: "kustomize.config.k8s.io/v1beta1"
                kind: "Kustomization"
                resources:
                  - "../../../../deploy/k8s/overlays/dev"
                patches:
                  - path: "cpu-throttled-deployment-demo-app-demo-app.yaml"
                    target:
                      kind: "Deployment"
                      name: "demo-app"
                  - path: "work-mem-spills-postgres-deployment-postgres.yaml"
                    target:
                      kind: "Deployment"
                      name: "postgres"
                """);
    }

    // ---------------------------------------------------------------- relocation

    @Test
    void relocationMovesEverythingIntoTheReplayNamespaceAndOwnsItsClusterObjects() throws Exception {
        List<ObjectNode> items = Relocation.relocate(rendered(), "fq-prove", 10);
        assertThat(items).noneMatch(i -> i.path("kind").asText().equals("Namespace"));
        for (ObjectNode i : items) {
            assertThat(i.path("metadata").path("labels").path(Relocation.REPLAY_LABEL).asText()).isEqualTo("true");
            if (!Relocation.CLUSTER_SCOPED.contains(i.path("kind").asText())) {
                assertThat(i.path("metadata").path("namespace").asText()).isEqualTo("fq-prove");
            }
        }
        ObjectNode crb = items.stream().filter(i -> i.path("kind").asText().equals("ClusterRoleBinding")).findFirst().orElseThrow();
        assertThat(crb.path("metadata").path("name").asText()).isEqualTo("fq-collector-cadvisor-fq-dev-fq-prove");
        assertThat(crb.path("roleRef").path("name").asText()).isEqualTo("fq-collector-cadvisor-fq-prove");
        assertThat(crb.path("subjects").get(0).path("namespace").asText()).isEqualTo("fq-prove");
        ObjectNode cr = items.stream().filter(i -> i.path("kind").asText().equals("ClusterRole")).findFirst().orElseThrow();
        assertThat(cr.path("metadata").path("name").asText()).isEqualTo("fq-collector-cadvisor-fq-prove");

        Map<String, JsonNode> d = Relocation.deployments(items);
        // the data survives the cold restarts: the claim stays (in the replay namespace, deleted with it)
        assertThat(d.get("postgres").path("spec").path("template").path("spec").path("volumes").get(0)
                .path("persistentVolumeClaim").path("claimName").asText()).isEqualTo("postgres-data");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.path("kind").asText()).isEqualTo("PersistentVolumeClaim");
            assertThat(i.path("metadata").path("namespace").asText()).isEqualTo("fq-prove");
        });
        assertThat(d.get("demo-app").path("spec").path("replicas").asInt()).isEqualTo(3);   // what is sized stays
        assertThat(d.get("fq-collector").toString()).contains("{\"name\":\"FQ_COLLECTOR_INTERVAL\",\"value\":\"10\"}");
        // the rendered input is untouched
        assertThat(rendered().toString()).doesNotContain("fq-prove");
    }

    @Test
    void kubectlOutputOfSeveralObjectsIsParsed() throws Exception {
        assertThat(Relocation.parseObjects("{\"kind\":\"Service\"}\n{\"kind\":\"Deployment\"}\n")).hasSize(2);
        assertThat(Relocation.parseObjects("{\"kind\":\"List\",\"items\":[{\"kind\":\"Service\"}]}")).hasSize(1);
    }

    // ---------------------------------------------------------------- load

    @Test
    void loadTimingsSkipTheWarmUpRoundAndCountFailures() {
        InfraMeasurement.Load l = InfraMeasurement.Load.parse("""
                timing\t1\t/a\t200\t2.500
                timing\t1\t/b\t200\t0.300
                round 2: /b -> HTTP 503
                timing\t2\t/a\t200\t0.120
                timing\t2\t/b\t503\t0.010
                timing\t3\t/a\t200\t0.100
                timing\t3\t/b\t200\t0,200
                traffic: 3 rounds x 2 endpoints on http://demo-app:8080 (errors: 1)
                """);
        assertThat(l.requests()).isEqualTo(6);
        assertThat(l.errors()).isEqualTo(1);
        assertThat(l.latencies()).containsEntry("/a", List.of(0.120, 0.100)).containsEntry("/b", List.of(0.200));
        assertThat(InfraMeasurement.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 0.95)).isEqualTo(10.0);
        assertThat(InfraMeasurement.percentile(List.of(), 0.95)).isNaN();
    }

    // ---------------------------------------------------------------- judge

    static Map<String, List<Double>> latencies(double authors, double ranking) {
        Map<String, List<Double>> m = new LinkedHashMap<>();
        m.put("/authors?limit=20", List.of(authors, authors, authors));
        m.put("/reviews/ranking?page=400&size=50", List.of(ranking, ranking, ranking));
        return m;
    }

    static Map<String, InfraMeasurement.Rollout> ready(String... notReady) {
        Map<String, InfraMeasurement.Rollout> m = new LinkedHashMap<>();
        for (String w : List.of("Deployment/demo-app", "Deployment/postgres")) {
            boolean bad = List.of(notReady).contains(w);
            m.put(w, new InfraMeasurement.Rollout(!bad, bad ? "2/3 Ready, demo-app-x restarted 2 times (Error)" : "1/1 Ready"));
        }
        return m;
    }

    static InfraMeasurement measure(String phase, ObjectNode infra, JsonNode temp, Map<String, List<Double>> lat,
                                    int errors, Map<String, InfraMeasurement.Rollout> rollouts) {
        return new InfraMeasurement(phase, "300s", infra, temp, new Sizer("300s", null).size(infra, temp), lat, 77, errors,
                rollouts);
    }

    static ObjectNode demo(ObjectNode infra) {
        for (JsonNode c : infra.path("containers")) {
            if (c.path("workload").asText().equals("Deployment/demo-app")) return (ObjectNode) c;
        }
        throw new AssertionError();
    }

    @Test
    void throttlingFixAcceptedWhenEveryCheckPasses() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        ObjectNode after = SizerTest.infra(1, 10);
        ObjectNode d = demo(after);
        d.with("limits").put("cpuMillis", 1000);
        ((ObjectNode) d.path("cpu")).put("throttledRatio", 0.02).put("usageP95Millis", 470);
        ((ObjectNode) d.path("startup")).put("throttledRatio", 0.30);
        d.put("startupSecondsMax", 41);
        SizingFinding i3 = SizerTest.only(before.findings(), Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO);

        Verdict v = InfraJudge.judge(List.of(i3), before,
                measure(i3.id(), after, SizerTest.tempQueries(), latencies(0.07, 1.9), 0, ready()));

        assertThat(v.reasons()).isEmpty();
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        assertThat(v.report().path("checks").toString()).contains("steady 40.0 % -> 2.0 %; startup 77 s -> 41 s (throttled 90.0 % -> 30.0 %)");
    }

    @Test
    void throttlingReducedButNotGoneIsAcceptedAndFlaggedForAFurtherStep() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        ObjectNode after = SizerTest.infra(1, 10);
        ObjectNode d = demo(after);
        d.with("limits").put("cpuMillis", 1000);
        ((ObjectNode) d.path("cpu")).put("throttledRatio", 0.33).put("usageP95Millis", 780);   // still >= 10 %, p95 >= 50 %
        ((ObjectNode) d.path("startup")).put("throttledRatio", 0.93);   // pinned near 100 %: +3 points is noise
        d.put("startupSecondsMax", 35);
        SizingFinding i3 = SizerTest.only(before.findings(), Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO);
        InfraMeasurement m = measure(i3.id(), after, SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        assertThat(m.findings()).extracting(SizingFinding::id).contains(i3.id());

        Verdict v = InfraJudge.judge(List.of(i3), before, m);
        assertThat(v.reasons()).isEmpty();
        assertThat(v.report().path("stillReported").asBoolean()).isTrue();
        assertThat(v.report().path("checks").toString()).contains("judged on the throttling reduction");
    }

    @Test
    void cpuFindingSilentOnTheReferenceForLackOfSamplesIsNotNew() {
        ObjectNode ref = SizerTest.infra(1, 10);
        ((ObjectNode) demo(ref).path("cpu")).put("rateSamples", 3);   // the CPU rules stay silent
        ((ObjectNode) demo(ref).path("cpu")).put("throttledRatio", 0.0);
        ((ObjectNode) demo(ref).path("startup")).put("throttledRatio", 0.0);
        InfraMeasurement before = measure("baseline", ref, SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        assertThat(before.findings()).noneMatch(f -> f.rule().equals("REQUESTS_BELOW_USAGE") && f.target().startsWith(InfraScorecard.DEMO));
        SizingFinding i4 = SizerTest.only(before.findings(), Sizer.Rule.WORK_MEM_SPILLS, "postgres:");
        ObjectNode after = SizerTest.infra(1, 10);
        ((ObjectNode) demo(after).path("cpu")).put("throttledRatio", 0.0);
        ((ObjectNode) demo(after).path("startup")).put("throttledRatio", 0.0);
        ((ObjectNode) after.path("postgres").path("settings").path("work_mem")).put("bytes", 32 * MI);
        ObjectNode none = (ObjectNode) SizerTest.tempQueries();
        ((ObjectNode) none.path("queries").get(0)).put("tempBlksWritten", 0);

        Verdict v = InfraJudge.judge(List.of(i4), before, measure(i4.id(), after, none, latencies(0.08, 0.5), 0, ready()));
        assertThat(v.reasons()).isEmpty();
        assertThat(v.report().path("notJudged").toString()).contains("requests-below-usage-deployment-demo-app-demo-app");
    }

    @Test
    void startupThrottlingRatioIsNotJudgedTheStartupTimeIs() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        SizingFinding i3 = SizerTest.only(before.findings(), Sizer.Rule.CPU_THROTTLED, InfraScorecard.DEMO);
        ObjectNode after = SizerTest.infra(1, 10);
        ObjectNode d = demo(after);
        d.with("limits").put("cpuMillis", 1000);
        ((ObjectNode) d.path("startup")).put("throttledRatio", 0.98);   // higher ratio, much shorter startup
        d.put("startupSecondsMax", 30);
        // same steady throttling (40 %): only the startup got better
        Verdict v = InfraJudge.judge(List.of(i3), before, measure(i3.id(), after, SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready()));
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);

        ((ObjectNode) d.path("cpu")).put("throttledRatio", 0.50);       // steady throttling up: rejected
        assertThat(InfraJudge.judge(List.of(i3), before, measure(i3.id(), after, SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready()))
                .reasons()).anySatisfy(r -> assertThat(r).startsWith("throttling not reduced"));
    }

    @Test
    void slowerLatencyCrashLoopAndNewFindingsReject() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        SizingFinding i1 = SizerTest.only(before.findings(), Sizer.Rule.JVM_HEAP_TOO_LARGE, InfraScorecard.DEMO);
        ObjectNode after = SizerTest.infra(1, 10);
        for (JsonNode j : after.path("jvms")) ((ObjectNode) j).put("heapMaxBytes", 460 * MI).put("maxRamPercentage", "60.0");
        demo(after).put("restarts", 2).put("oomKilled", 1);
        // a new finding on a restarted workload: max_connections lowered, the pool no longer fits
        ((ObjectNode) after.path("postgres").path("connections")).put("maxConnections", 10);

        Verdict v = InfraJudge.judge(List.of(i1), before,
                measure(i1.id(), after, SizerTest.tempQueries(), latencies(0.08, 3.1), 2, ready("Deployment/demo-app")));

        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).anySatisfy(r -> assertThat(r).startsWith("rollout not complete: Deployment/demo-app"))
                .anySatisfy(r -> assertThat(r).contains("container restarted").contains("1 OOMKilled"))
                .anySatisfy(r -> assertThat(r).isEqualTo("2 request(s) failed"))
                .anySatisfy(r -> assertThat(r).contains("/reviews/ranking?page=400&size=50: p95 3.100 s instead of 2.000 s"))
                .anySatisfy(r -> assertThat(r).startsWith("new finding: POOL_EXCEEDS_CONNECTIONS on postgres:Deployment/postgres"))
                // an OOMKill keeps the heap finding up even with the smaller heap
                .anySatisfy(r -> assertThat(r).startsWith("finding still reported: [" + i1.id()));
    }

    @Test
    void workMemAcceptedOnlyWhenTheStatementNoLongerSpills() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        SizingFinding i4 = SizerTest.only(before.findings(), Sizer.Rule.WORK_MEM_SPILLS, "postgres:");
        ObjectNode after = SizerTest.infra(1, 10);
        ((ObjectNode) after.path("postgres").path("settings").path("work_mem")).put("bytes", 32 * MI);
        // capped at 32MB: the 20 MiB per call sort still spills (in-memory needs ~2x the spill)
        ObjectNode spilling = (ObjectNode) SizerTest.tempQueries();
        ((ObjectNode) spilling.path("queries").get(0)).put("tempBlksWritten", 6400);

        Verdict v = InfraJudge.judge(List.of(i4), before,
                measure(i4.id(), after, spilling, latencies(0.08, 1.5), 0, ready()));
        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).singleElement().satisfies(r -> assertThat(r)
                .startsWith("still spills to disk: statements of GET /reviews/ranking: 6400 temporary block(s) written (50 MiB)")
                .contains("trap 7"));

        ObjectNode none = (ObjectNode) SizerTest.tempQueries();
        ((ObjectNode) none.path("queries").get(0)).put("tempBlksWritten", 0);
        ((ObjectNode) after.path("postgres").path("database")).put("tempBytes", 0).put("tempFiles", 0);
        assertThat(InfraJudge.judge(List.of(i4), before, measure(i4.id(), after, none, latencies(0.08, 0.4), 0, ready()))
                .status()).isEqualTo(Verdict.Status.ACCEPTED);
    }

    @Test
    void findingNotReproducedWithoutThePatchIsInconclusive() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        SizingFinding i6 = SizerTest.only(findings(3, 20), Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:");
        assertThat(InfraJudge.judge(List.of(i6), before, before).status()).isEqualTo(Verdict.Status.INCONCLUSIVE);
    }

    @Test
    void poolFixAcceptedWhenTheRolloutCompletesWithoutRefusedConnections() {
        InfraMeasurement before = measure("baseline", SizerTest.infra(3, 20), SizerTest.tempQueries(), latencies(0.08, 2.0), 3,
                ready("Deployment/demo-app"));
        SizingFinding i6 = SizerTest.only(before.findings(), Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:");
        ObjectNode afterInfra = SizerTest.infra(3, 14);
        ((ObjectNode) afterInfra.path("containers").get(1).path("memory")).put("workingSetMaxBytes", 400 * MI);   // the collector
        InfraMeasurement after = measure(i6.id(), afterInfra, SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        Verdict v = InfraJudge.judge(List.of(i6), before, after);
        assertThat(v.reasons()).isEmpty();
        assertThat(v.report().path("rollouts").path("Deployment/demo-app").path("before").asText()).contains("2/3 Ready");
        // the collector is not restarted by the replay: a finding on it is reported, not held against the patch
        assertThat(v.report().path("notRestarted").toString()).contains("requests-below-usage-deployment-fq-collector-collector");
        assertThat(InfraJudge.workload("postgres:Deployment/postgres")).isEqualTo("Deployment/postgres");
    }

    @Test
    void controlDriftOfTheBaselinePlayedTwice() {
        InfraMeasurement a = measure("baseline", SizerTest.infra(1, 10), SizerTest.tempQueries(), latencies(0.08, 2.0), 0, ready());
        ObjectNode i = SizerTest.infra(1, 10);
        demo(i).put("startupSecondsMax", 90);
        // /authors: +20 ms, noise; ranking: +0.5 s, a drift of 25 %
        InfraMeasurement b = measure("baseline-control", i, SizerTest.tempQueries(), latencies(0.10, 2.5), 0, ready());
        ObjectNode d = InfraJudge.drift(a, b);
        assertThat(d.path("startupSeconds").path("Deployment/demo-app/demo-app").toString()).isEqualTo("[77.0,90.0]");
        assertThat(d.path("p95MaxRelativeDifference").asDouble()).isEqualTo(0.25);
        assertThat(d.path("p95MaxDifferencePath").asText()).isEqualTo("/reviews/ranking?page=400&size=50");
        assertThat(d.path("steadyThrottledRatio").has("Deployment/fq-collector/collector")).isFalse();   // not restarted
    }

    @Test
    void markdownSummary() {
        ObjectNode r = JSON.createObjectNode().put("overlay", "deploy/k8s/overlays/dev").put("profile", "S").put("rounds", 30);
        var proofs = r.putArray("proofs");
        proofs.addObject().put("finding", "cpu-throttled-deployment-demo-app-demo-app").put("rule", "CPU_THROTTLED").put("target", "Deployment/demo-app/demo-app")
                .put("change", "demo-app/demo-app: limits cpu 500m -> 1").put("status", "ACCEPTED").putArray("reasons");
        proofs.addObject().put("finding", "cpu-throttled-deployment-demo-app-demo-app").put("step", 2)
                .put("rule", "CPU_THROTTLED").put("target", "Deployment/demo-app/demo-app")
                .put("change", "demo-app/demo-app: limits cpu 1 -> 2").put("status", "REJECTED").putArray("reasons")
                .add("slower startup: x");
        proofs.addObject().put("finding", "shared-buffers-small-postgres-deployment-postgres").put("rule", "SHARED_BUFFERS_SMALL").put("target", "postgres:Deployment/postgres")
                .put("status", "INCONCLUSIVE").putArray("reasons").add("no longer reported");
        ObjectNode acc = r.putObject("accepted").put("overlay", "overlays/accepted");
        acc.putArray("patches").add("cpu-throttled-deployment-demo-app-demo-app");
        acc.putArray("findingsLeft").add("WORK_MEM_SPILLS postgres:Deployment/postgres");
        ObjectNode ctl = r.putObject("control").put("p95MaxRelativeDifference", 0.18).put("p95MaxDifferencePath", "/books?limit=50");
        ctl.putObject("startupSeconds").putArray("Deployment/demo-app/demo-app").add(65.0).add(71.0);
        assertThat(InfraProver.markdown(r)).contains("| CPU_THROTTLED `Deployment/demo-app/demo-app` | demo-app/demo-app: "
                + "limits cpu 500m -> 1 | ACCEPTED | every check passed |")
                .contains("| CPU_THROTTLED `Deployment/demo-app/demo-app` (step 2) | demo-app/demo-app: limits cpu 1 -> 2 | REJECTED |")
                .contains("| SHARED_BUFFERS_SMALL `postgres:Deployment/postgres` | — | INCONCLUSIVE | no longer reported |")
                .contains("Accepted patches, proven together (`overlays/accepted`): cpu-throttled-deployment-demo-app-demo-app. "
                        + "Still reported with all of them: WORK_MEM_SPILLS postgres:Deployment/postgres.")
                .contains("Control, the baseline played twice (the replay's own noise): Deployment/demo-app/demo-app startup "
                        + "65 s then 71 s; p95 up to 18 % apart (/books?limit=50).")
                .contains("phase 6 replay: 1/2 recommendation(s) proven on deploy/k8s/overlays/dev (profile S, 30 timed rounds)");
    }

    @Test
    void provingOrderPutsTheCpuFirstAndTheRequestsLast() {
        List<SizingFinding> fs = InfraProver.ordered(findings(1, 10));
        assertThat(fs.get(0).rule()).isEqualTo("CPU_THROTTLED");
        assertThat(fs.get(fs.size() - 1).rule()).isEqualTo("REQUESTS_BELOW_USAGE");
        assertThat(fs).extracting(SizingFinding::rule).containsSubsequence("CPU_THROTTLED", "JVM_HEAP_TOO_LARGE",
                "WORK_MEM_SPILLS", "SHARED_BUFFERS_SMALL", "REQUESTS_BELOW_USAGE");
    }
}
