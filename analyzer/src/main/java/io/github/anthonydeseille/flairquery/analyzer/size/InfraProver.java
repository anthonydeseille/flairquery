package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Infrastructure prover (phase 6, step 3): replays the sizing recommendations on kind before any merge request.
 *
 * <ol>
 *   <li>the overlay being sized is rendered and moved into a throwaway namespace ({@link Relocation}), deployed with
 *       one replica per workload, and the corpus is loaded once;</li>
 *   <li><b>baseline</b>: the application workloads (every Deployment but FlairQuery's own collector and store) are
 *       stopped, then started cold with the overlay as is, the same load is played (timed per request), and the
 *       collector's measurements over the phase are sized ({@link Sizer});</li>
 *   <li>one <b>candidate</b> phase per finding of the baseline, in {@link #ORDER} (a starved CPU distorts every
 *       other measurement, the requests depend on the usage after the other changes): the overlay plus every patch
 *       accepted so far plus the finding's Kustomize patch ({@link InfraPatches}), computed on the latest accepted
 *       measurement (the reference) and judged against it ({@link InfraJudge}). An accepted patch makes its
 *       measurement the new reference: patches are proven together, as they would be merged (a smaller heap only
 *       holds with enough CPU for the GC). A finding no longer reported on the reference is inconclusive;</li>
 *   <li>a <b>control</b> phase: the baseline again, its drift from the first one reported (the replay's own noise);</li>
 *   <li>the namespace and its cluster-scoped objects are deleted, even on failure (unless {@code keep}).</li>
 * </ol>
 * Writes the patches, the overlays, every measurement, {@code report.json} and {@code proofs.md}.
 */
public final class InfraProver {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** FlairQuery's own workloads: they observe the replay and are never restarted by it. */
    static final Set<String> OBSERVERS = Set.of("fq-collector", "fq-store");
    static final Duration ROLLOUT_TIMEOUT = Duration.ofMinutes(10);
    static final Duration LOAD_TIMEOUT = Duration.ofMinutes(30);
    /** A pod restarted this many times is crash-looping: its rollout will not complete. */
    static final int CRASH_LOOP_RESTARTS = 2;

    /**
     * @param repo              repository root (deploy/, scripts/)
     * @param overlay           the Kustomize overlay being sized
     * @param out               output directory
     * @param namespace         throwaway namespace (deleted first if it exists, and at the end)
     * @param profile           corpus profile loaded once (S, M)
     * @param rounds            timed load rounds per phase (plus one warm-up round)
     * @param collectorInterval collector cycle in the replay, seconds
     * @param loadUrl           base URL the load job calls
     * @param only              finding ids to prove (empty: all)
     * @param keep              keep the namespace at the end
     * @param database          the database Deployment, started (and Ready) before the applications in each phase
     */
    public record Options(Path repo, Path overlay, Path out, String namespace, String profile, int rounds,
                          int collectorInterval, String loadUrl, Set<String> only, boolean keep, String database) { }

    private final Options o;
    private final Kube kube;
    private final Instant started = Instant.now();

    public InfraProver(Options options, String kubectl, String context) {
        this.o = options;
        this.kube = new Kube(kubectl, context, options.out().resolve("kubectl.log"));
    }

    public int run() throws Exception {
        Files.createDirectories(o.out());
        log("replay of " + o.overlay() + " in namespace " + o.namespace() + " (context " + kube.context() + ")");
        List<ObjectNode> baseItems = render(o.overlay());
        Map<String, JsonNode> deployments = Relocation.deployments(baseItems);
        List<String> cycled = deployments.keySet().stream().filter(n -> !OBSERVERS.contains(n)).toList();
        if (cycled.isEmpty()) throw new IOException("no application Deployment in " + o.overlay());
        // interrupted (Ctrl-C, kill): the namespace and its cluster-scoped objects are deleted all the same
        Thread hook = new Thread(() -> {
            try {
                cleanup(true);
            } catch (Exception ignored) {
                // best effort
            }
        }, "fq-prove-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            setup(baseItems, cycled);
            InfraMeasurement baseline = phase("baseline", baseItems, cycled);
            print(baseline);
            if (baseline.errors() > 0 && baseline.rollouts().values().stream().allMatch(InfraMeasurement.Rollout::complete)) {
                // everything rolled out, yet requests fail: the replay itself is broken (data lost, wrong URL)
                throw new IOException(baseline.errors() + " request(s) failed in the baseline although every workload "
                        + "rolled out: replay environment broken, nothing can be proven (see measurements/baseline.json)");
            }

            List<Map<String, Object>> proofs = new ArrayList<>();
            List<InfraPatches.Patch> accepted = new ArrayList<>();
            InfraMeasurement reference = baseline;          // the overlay plus every patch accepted so far
            Map<String, JsonNode> current = deployments;
            for (SizingFinding first : ordered(baseline.findings())) {
                if (!o.only().isEmpty() && !o.only().contains(first.id())) continue;
                for (int step = 1; step <= MAX_STEPS; step++) {
                    Map<String, Object> proof = new LinkedHashMap<>();
                    proof.put("finding", first.id());
                    proof.put("rule", first.rule());
                    proof.put("target", first.target());
                    proof.put("title", first.title());
                    if (step > 1) proof.put("step", step);
                    proofs.add(proof);
                    String observer = OBSERVERS.stream().filter(n -> first.target().contains("/" + n)).findFirst().orElse(null);
                    if (observer != null) {
                        skip(proof, "SKIPPED", "FlairQuery's own workload (" + observer + "): not restarted by the replay");
                        break;
                    }
                    // sized again on the reference: an earlier patch may have resolved it or changed its figures
                    SizingFinding f = reference.findings().stream().filter(x -> x.id().equals(first.id())).findFirst()
                            .orElse(null);
                    if (f == null) {
                        skip(proof, "INCONCLUSIVE", "no longer reported once the patches accepted before were applied ("
                                + accepted.stream().map(InfraPatches.Patch::findingId).toList() + ")");
                        break;
                    }
                    proof.put("title", f.title());
                    InfraPatches.Patch patch;
                    try {
                        patch = InfraPatches.of(f, current);
                    } catch (InfraPatches.Unsupported e) {
                        skip(proof, "SKIPPED", "no patch: " + e.getMessage());
                        break;
                    }
                    Files.createDirectories(o.out().resolve("patches"));
                    Files.writeString(o.out().resolve("patches").resolve(patch.fileName()), patch.yaml());
                    proof.put("patch", "patches/" + patch.fileName());
                    proof.put("change", patch.summary());
                    // a further step replaces the finding's earlier patch
                    List<InfraPatches.Patch> stack = new ArrayList<>(accepted);
                    stack.removeIf(x -> x.findingId().equals(patch.findingId()));
                    proof.put("on top of", stack.stream().map(InfraPatches.Patch::findingId).toList());
                    log("proving " + f.id() + (step > 1 ? " (step " + step + ")" : "") + ": " + patch.summary()
                            + (stack.isEmpty() ? "" : " (on top of " + stack.size() + " accepted patch(es))"));
                    stack.add(patch);
                    String phaseName = f.id() + (step > 1 ? "-step" + step : "");
                    List<ObjectNode> items = render(overlay(phaseName, stack));
                    InfraMeasurement candidate;
                    try {
                        candidate = phase(phaseName, items, cycled);
                    } catch (PatchRefused e) {
                        skip(proof, "REJECTED", "patch refused by the API server: " + e.getMessage());
                        break;
                    }
                    print(candidate);
                    Verdict v = InfraJudge.judge(List.of(f), reference, candidate);
                    verdict(proof, v);
                    if (!v.accepted()) break;
                    accepted.clear();
                    accepted.addAll(stack);
                    reference = candidate;
                    current = Relocation.deployments(items);
                    if (!v.report().path("stillReported").asBoolean()) break;   // otherwise: next step on top
                }
            }
            log("control: the baseline again (the replay's own noise)");
            InfraMeasurement control = phase("baseline-control", baseItems, cycled);
            ObjectNode drift = InfraJudge.drift(baseline, control);
            log("  drift: " + drift);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("patches", accepted.stream().map(InfraPatches.Patch::findingId).toList());
            result.put("measuredIn", reference.phase());
            result.put("findingsLeft", reference.findings().stream().map(f -> f.rule() + " " + f.target()).toList());
            if (!accepted.isEmpty()) {
                overlay("accepted", accepted);
                result.put("overlay", "overlays/accepted");
            }
            report(baseline, proofs, result, drift);
            return 0;
        } finally {
            cleanup(true);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // already shutting down
            }
        }
    }

    // ---------------------------------------------------------------- phases

    private void setup(List<ObjectNode> items, List<String> cycled) throws Exception {
        log("fresh namespace " + o.namespace());
        cleanup(false);
        ObjectNode ns = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Namespace");
        ns.putObject("metadata").put("name", o.namespace()).putObject("labels")
                .put("app.kubernetes.io/part-of", "flairquery").put(Relocation.REPLAY_LABEL, "true");
        kube.run(ns.toString(), Duration.ofMinutes(1), "apply", "-f", "-");
        ObjectNode cm = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "ConfigMap");
        cm.putObject("metadata").put("name", "fq-prove-traffic").put("namespace", o.namespace());
        cm.putObject("data").put("traffic.sh", Files.readString(o.repo().resolve("scripts/traffic.sh")));
        kube.run(cm.toString(), Duration.ofMinutes(1), "apply", "-f", "-");

        // one replica each while loading the corpus (an overlay may oversubscribe connections on purpose)
        List<ObjectNode> single = new ArrayList<>();
        for (ObjectNode i : items) {
            ObjectNode c = i.deepCopy();
            if (c.path("kind").asText().equals("Deployment")) ((ObjectNode) c.path("spec")).put("replicas", 1);
            single.add(c);
        }
        kube.run(Relocation.list(single).toString(), Duration.ofMinutes(2), "apply", "-f", "-");
        Map<String, InfraMeasurement.Rollout> r = rollouts(Relocation.deployments(single).keySet().stream().toList());
        r.forEach((k, v) -> log("  " + k + ": " + v.detail()));
        if (r.values().stream().anyMatch(x -> !x.complete())) throw new IOException("setup did not roll out: " + r);

        log("corpus profile " + o.profile());
        String seed = Files.readString(o.repo().resolve("deploy/k8s/jobs/seed.yaml"))
                .replace("__PROFILE__", o.profile()).replace("__FORCE__", "none");
        kube.run(seed, Duration.ofMinutes(1), "-n", o.namespace(), "apply", "-f", "-");
        String status = waitJob("fq-seed", Duration.ofMinutes(40));
        if (!status.equals("succeeded")) {
            throw new IOException("corpus loading " + status + ": " + kube.exec(null, Duration.ofMinutes(1), "-n",
                    o.namespace(), "logs", "job/fq-seed", "--tail=20").out());
        }
    }

    /** Application workloads stopped, then started cold with {@code items}, load, measurement. */
    InfraMeasurement phase(String name, List<ObjectNode> items, List<String> cycled) throws Exception {
        log("phase " + name + ": stopping " + cycled);
        kube.run(null, Duration.ofMinutes(1), withArgs(List.of("-n", o.namespace(), "scale", "--replicas=0"),
                cycled.stream().map(d -> "deployment/" + d).toList()));
        for (String d : cycled) waitNoPod(d);
        Instant t0 = Instant.now();
        if (cycled.contains(o.database())) {
            // the database first, Ready before the applications start: their startup must not include waiting for it
            List<ObjectNode> dbOnly = new ArrayList<>();
            for (ObjectNode i : items) {
                ObjectNode c = i.deepCopy();
                String n = c.path("metadata").path("name").asText();
                if (c.path("kind").asText().equals("Deployment") && cycled.contains(n) && !n.equals(o.database())) {
                    ((ObjectNode) c.path("spec")).put("replicas", 0);
                }
                dbOnly.add(c);
            }
            apply(dbOnly);
            InfraMeasurement.Rollout db = rollouts(List.of(o.database())).get(o.database());
            log("  " + o.database() + " (first): " + db.detail());
        }
        apply(items);
        Map<String, InfraMeasurement.Rollout> rollouts = rollouts(cycled);
        rollouts.forEach((k, v) -> log("  " + k + ": " + v.detail()));

        log("  load: " + o.rounds() + " timed round(s) + 1 warm-up");
        InfraMeasurement.Load load = InfraMeasurement.Load.parse(load());
        log("  " + load.requests() + " request(s), " + load.errors() + " failed");
        Thread.sleep(Duration.ofSeconds(2L * o.collectorInterval() + 5).toMillis());   // last cycles after the load

        // exactly the phase: a margin before t0 would bring the previous phase's pods in
        long seconds = Duration.between(t0, Instant.now()).toSeconds();
        String window = seconds + "s";
        JsonNode infra;
        JsonNode temp;
        try (Kube.PortForward pf = kube.portForward(o.namespace(), "fq-collector", 9465)) {
            JsonSource collector = JsonSource.http(pf.url());
            infra = collector.get("/fq/collector/infra?window=" + window);
            temp = collector.get("/fq/collector/queries?order=temp&limit=10&window=" + window);
        }
        List<SizingFinding> findings = new Sizer(window, null).size(infra, temp);
        Map<String, InfraMeasurement.Rollout> named = new LinkedHashMap<>();
        rollouts.forEach((k, v) -> named.put("Deployment/" + k, v));
        InfraMeasurement m = new InfraMeasurement(name, window, infra, temp, findings, load.latencies(),
                load.requests(), load.errors(), named);
        Files.createDirectories(o.out().resolve("measurements"));
        JSON.writeValue(o.out().resolve("measurements").resolve(name + ".json").toFile(), m);
        return m;
    }

    private void apply(List<ObjectNode> items) throws Exception {
        Kube.Result applied = kube.exec(Relocation.list(items).toString(), Duration.ofMinutes(2), "apply", "-f", "-");
        if (!applied.ok()) throw new PatchRefused(applied.err().strip());
    }

    /** Waits until each Deployment is Ready, crash-loops or times out. */
    private Map<String, InfraMeasurement.Rollout> rollouts(List<String> names) throws Exception {
        Map<String, InfraMeasurement.Rollout> done = new LinkedHashMap<>();
        Instant deadline = Instant.now().plus(ROLLOUT_TIMEOUT);
        while (done.size() < names.size()) {
            for (String d : names) {
                if (done.containsKey(d)) continue;
                JsonNode dep = JSON.readTree(kube.run(null, Duration.ofMinutes(1), "-n", o.namespace(), "get",
                        "deployment/" + d, "-o", "json"));
                int want = dep.path("spec").path("replicas").asInt(1);
                JsonNode st = dep.path("status");
                boolean observed = st.path("observedGeneration").asLong() >= dep.path("metadata").path("generation").asLong();
                int ready = st.path("readyReplicas").asInt(0);
                JsonNode pods = JSON.readTree(kube.run(null, Duration.ofMinutes(1), "-n", o.namespace(), "get", "pods",
                        "-l", selector(dep), "-o", "json")).path("items");
                int restarts = 0;
                String crashing = null;
                for (JsonNode p : pods) {
                    for (JsonNode cs : p.path("status").path("containerStatuses")) {
                        restarts = Math.max(restarts, cs.path("restartCount").asInt());
                        if (cs.path("restartCount").asInt() >= CRASH_LOOP_RESTARTS) {
                            crashing = p.path("metadata").path("name").asText() + " restarted "
                                    + cs.path("restartCount").asInt() + " times ("
                                    + cs.path("lastState").path("terminated").path("reason").asText("?") + ")";
                        }
                    }
                }
                if (observed && ready == want && st.path("updatedReplicas").asInt() == want
                        && st.path("replicas").asInt() == want) {
                    done.put(d, new InfraMeasurement.Rollout(true, ready + "/" + want + " Ready"
                            + (restarts > 0 ? " (" + restarts + " restart(s))" : "")));
                } else if (crashing != null) {
                    done.put(d, new InfraMeasurement.Rollout(false, ready + "/" + want + " Ready, " + crashing));
                } else if (Instant.now().isAfter(deadline)) {
                    done.put(d, new InfraMeasurement.Rollout(false, ready + "/" + want + " Ready after "
                            + ROLLOUT_TIMEOUT.toMinutes() + " min"));
                }
            }
            if (done.size() < names.size()) Thread.sleep(5000);
        }
        Map<String, InfraMeasurement.Rollout> ordered = new LinkedHashMap<>();
        names.forEach(n -> ordered.put(n, done.get(n)));
        return ordered;
    }

    private void waitNoPod(String deployment) throws Exception {
        JsonNode dep = JSON.readTree(kube.run(null, Duration.ofMinutes(1), "-n", o.namespace(), "get",
                "deployment/" + deployment, "-o", "json"));
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            JsonNode pods = JSON.readTree(kube.run(null, Duration.ofMinutes(1), "-n", o.namespace(), "get", "pods",
                    "-l", selector(dep), "-o", "json")).path("items");
            if (pods.isEmpty()) return;
            Thread.sleep(2000);
        }
        throw new IOException("pods of " + deployment + " still running 3 min after scaling it to 0");
    }

    /** Runs the load job, returns its output. */
    private String load() throws Exception {
        String name = "fq-prove-load";
        kube.exec(null, Duration.ofMinutes(2), "-n", o.namespace(), "delete", "job", name, "--ignore-not-found",
                "--wait=true");
        ObjectNode job = JSON.createObjectNode().put("apiVersion", "batch/v1").put("kind", "Job");
        job.putObject("metadata").put("name", name).put("namespace", o.namespace()).putObject("labels")
                .put("app.kubernetes.io/name", name);
        ObjectNode spec = job.putObject("spec").put("backoffLimit", 0);
        ObjectNode tpl = spec.putObject("template");
        tpl.putObject("metadata").putObject("labels").put("app.kubernetes.io/name", name);
        ObjectNode pod = tpl.putObject("spec").put("restartPolicy", "Never");
        ObjectNode c = pod.putArray("containers").addObject().put("name", "load").put("image", "flairquery/tools:dev")
                .put("imagePullPolicy", "IfNotPresent");
        c.putArray("command").add("bash").add("/traffic/traffic.sh").add(o.loadUrl()).add(Integer.toString(o.rounds() + 1));
        c.putArray("env").addObject().put("name", "FQ_TRAFFIC_TIMINGS").put("value", "1");
        c.putArray("volumeMounts").addObject().put("name", "traffic").put("mountPath", "/traffic");
        ObjectNode res = c.putObject("resources");
        res.putObject("requests").put("cpu", "50m").put("memory", "32Mi");
        res.putObject("limits").put("cpu", "500m").put("memory", "128Mi");
        pod.putArray("volumes").addObject().put("name", "traffic").putObject("configMap").put("name", "fq-prove-traffic");
        kube.run(job.toString(), Duration.ofMinutes(1), "apply", "-f", "-");
        String status = waitJob(name, LOAD_TIMEOUT);
        String out = kube.exec(null, Duration.ofMinutes(1), "-n", o.namespace(), "logs", "job/" + name).out();
        if (status.equals("timed out")) throw new IOException("load did not finish in " + LOAD_TIMEOUT.toMinutes() + " min");
        return out;   // a failed job (requests not answered) is a measurement, not an error
    }

    /** "succeeded", "failed" or "timed out". */
    private String waitJob(String name, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode st = JSON.readTree(kube.run(null, Duration.ofMinutes(1), "-n", o.namespace(), "get", "job", name,
                    "-o", "json")).path("status");
            if (st.path("succeeded").asInt() > 0) return "succeeded";
            if (st.path("failed").asInt() > 0) return "failed";
            Thread.sleep(5000);
        }
        return "timed out";
    }

    /** @param atEnd false: before the replay (a previous run's namespace is always deleted) */
    private void cleanup(boolean atEnd) throws Exception {
        if (atEnd && o.keep()) {
            log("namespace " + o.namespace() + " kept (--keep)");
            return;
        }
        kube.exec(null, Duration.ofMinutes(6), "delete", "namespace", o.namespace(), "--ignore-not-found",
                "--wait=true", "--timeout=5m");
        kube.exec(null, Duration.ofMinutes(1), "delete", "clusterrolebinding,clusterrole", "-l",
                Relocation.REPLAY_LABEL + "=true", "--ignore-not-found");
    }

    // ---------------------------------------------------------------- manifests

    /** Renders an overlay into relocated objects. */
    private List<ObjectNode> render(Path overlay) throws Exception {
        String yaml = kube.run(null, Duration.ofMinutes(2), "kustomize", "--load-restrictor=LoadRestrictionsNone",
                overlay.toString());
        String json = kube.run(yaml, Duration.ofMinutes(2), "create", "--dry-run=client", "-o", "json", "-f", "-");
        return Relocation.relocate(Relocation.parseObjects(json), o.namespace(), o.collectorInterval());
    }

    /** Overlay {@code out/overlays/<name>}: the overlay being sized plus {@code patches}. */
    private Path overlay(String name, List<InfraPatches.Patch> patches) throws IOException {
        Path dir = o.out().resolve("overlays").resolve(name);
        Files.createDirectories(dir);
        for (InfraPatches.Patch p : patches) Files.writeString(dir.resolve(p.fileName()), p.yaml());
        String base = dir.toAbsolutePath().normalize().relativize(o.overlay().toAbsolutePath().normalize()).toString();
        Files.writeString(dir.resolve("kustomization.yaml"), InfraPatches.kustomization(base, patches));
        return dir;
    }

    private static String selector(JsonNode deployment) {
        StringJoiner s = new StringJoiner(",");
        deployment.path("spec").path("selector").path("matchLabels").fields()
                .forEachRemaining(e -> s.add(e.getKey() + "=" + e.getValue().asText()));
        return s.toString();
    }

    private static String[] withArgs(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all.toArray(String[]::new);
    }

    // ---------------------------------------------------------------- output

    private static void skip(Map<String, Object> proof, String status, String why) {
        proof.put("status", status);
        proof.put("reasons", List.of(why));
        System.out.println("  " + status + " " + proof.get("finding") + ": " + why);
    }

    /** Steps tried for a finding still reported after an accepted patch (CPU throttling reduced, not gone). */
    static final int MAX_STEPS = 3;

    /** Proving order: what distorts every other measurement first (a starved CPU), the requests last. */
    static final List<String> ORDER = List.of(Sizer.Rule.CPU_THROTTLED.name(), Sizer.Rule.POOL_EXCEEDS_CONNECTIONS.name(),
            Sizer.Rule.JVM_HEAP_TOO_LARGE.name(), Sizer.Rule.WORK_MEM_SPILLS.name(), Sizer.Rule.SHARED_BUFFERS_SMALL.name(),
            Sizer.Rule.REQUESTS_BELOW_USAGE.name());

    static List<SizingFinding> ordered(List<SizingFinding> findings) {
        List<SizingFinding> out = new ArrayList<>(findings);
        out.sort(java.util.Comparator.comparingInt(f -> ORDER.indexOf(f.rule())));
        return out;
    }

    /** The API server refused the manifests of a phase (an invalid patch). */
    static final class PatchRefused extends IOException {
        PatchRefused(String message) {
            super(message);
        }
    }

    private static void verdict(Map<String, Object> proof, Verdict v) {
        proof.put("status", v.status().name());
        proof.put("reasons", v.reasons());
        proof.put("report", v.report());
        System.out.println("  " + v.status() + (v.reasons().isEmpty() ? "" : ": " + String.join("; ", v.reasons())));
        for (JsonNode c : v.report().path("checks")) {
            System.out.printf("    %s %-30s %s%n", c.path("passed").asBoolean() ? "ok  " : "FAIL",
                    c.path("check").asText(), c.path("detail").asText());
        }
    }

    private void print(InfraMeasurement m) {
        System.out.println("  " + m.findings().size() + " sizing finding(s) over " + m.window() + ":");
        m.findings().forEach(f -> System.out.println("    " + f.rule() + " " + f.target() + ": " + f.title()));
    }

    private void report(InfraMeasurement baseline, List<Map<String, Object>> proofs, Map<String, Object> result,
                        ObjectNode drift) throws IOException {
        ObjectNode r = JSON.createObjectNode();
        r.put("overlay", o.overlay().toString());
        r.put("namespace", o.namespace());
        r.put("profile", o.profile());
        r.put("rounds", o.rounds());
        r.put("startedAt", started.toString());
        r.put("minutes", Duration.between(started, Instant.now()).toMinutes());
        ObjectNode b = r.putObject("baseline");
        b.put("window", baseline.window());
        b.set("findings", JSON.valueToTree(baseline.findings().stream().map(SizingFinding::id).toList()));
        b.set("rollouts", JSON.valueToTree(baseline.rollouts()));
        b.put("requests", baseline.requests()).put("errors", baseline.errors());
        r.set("proofs", JSON.valueToTree(proofs));
        r.set("accepted", JSON.valueToTree(result));
        r.set("control", drift);
        JSON.writeValue(o.out().resolve("report.json").toFile(), r);
        String md = markdown(r);
        Files.writeString(o.out().resolve("proofs.md"), md);
        System.out.println();
        System.out.println(md);
    }

    static String markdown(JsonNode r) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Finding | Change | Verdict | Why |\n|---|---|---|---|\n");
        Set<String> accepted = new java.util.TreeSet<>();
        Set<String> total = new java.util.TreeSet<>();
        for (JsonNode p : r.path("proofs")) {
            total.add(p.path("finding").asText());
            if (p.path("status").asText().equals("ACCEPTED")) accepted.add(p.path("finding").asText());
            List<String> why = new ArrayList<>();
            p.path("reasons").forEach(x -> why.add(x.asText()));
            sb.append("| ").append(p.path("rule").asText()).append(" `").append(p.path("target").asText()).append('`')
                    .append(p.has("step") ? " (step " + p.path("step").asInt() + ")" : "").append(" | ")
                    .append(p.path("change").asText("—")).append(" | ").append(p.path("status").asText()).append(" | ")
                    .append(why.isEmpty() ? "every check passed" : String.join("; ", why).replace("|", "\\|"))
                    .append(" |\n");
        }
        JsonNode acc = r.path("accepted");
        if (acc.path("patches").size() > 0) {
            List<String> ids = new ArrayList<>();
            acc.path("patches").forEach(x -> ids.add(x.asText()));
            List<String> left = new ArrayList<>();
            acc.path("findingsLeft").forEach(x -> left.add(x.asText()));
            sb.append("\nAccepted patches, proven together (`").append(acc.path("overlay").asText()).append("`): ")
                    .append(String.join(", ", ids)).append(". Still reported with all of them: ")
                    .append(left.isEmpty() ? "nothing" : String.join("; ", left)).append(".\n");
        }
        JsonNode ctl = r.path("control");
        if (!ctl.isMissingNode()) {
            List<String> parts = new ArrayList<>();
            ctl.path("startupSeconds").fields().forEachRemaining(e -> parts.add(e.getKey() + " startup "
                    + Math.round(e.getValue().get(0).asDouble()) + " s then " + Math.round(e.getValue().get(1).asDouble()) + " s"));
            parts.add("p95 up to " + Math.round(ctl.path("p95MaxRelativeDifference").asDouble() * 100) + " % apart ("
                    + ctl.path("p95MaxDifferencePath").asText("-") + ")");
            sb.append("\nControl, the baseline played twice (the replay's own noise): ").append(String.join("; ", parts))
                    .append(".\n");
        }
        sb.append("\nphase 6 replay: ").append(accepted.size()).append('/').append(total.size()).append(" recommendation(s) proven on ")
                .append(r.path("overlay").asText()).append(" (profile ").append(r.path("profile").asText()).append(", ")
                .append(r.path("rounds").asInt()).append(" timed rounds)\n");
        return sb.toString();
    }

    private void log(String msg) {
        System.out.printf("[prove-infra %4ds] %s%n", Duration.between(started, Instant.now()).toSeconds(), msg);
    }
}
