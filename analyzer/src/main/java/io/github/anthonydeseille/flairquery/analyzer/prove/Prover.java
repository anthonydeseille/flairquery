package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Proves fixes before anything is proposed. For one finding and one or more patches:
 * <ol>
 *   <li>copies the application, applies each patch on its own copy, builds the baseline and every candidate;</li>
 *   <li>starts a throw-away PostgreSQL, lets the baseline create the schema, seeds it once (template database);</li>
 *   <li>runs the baseline, and each candidate in turn next to it, with the agent, each on its own clone of the
 *       template: one pass over every request (responses recorded, also the warm-up), then {@code rounds}
 *       passes alternated between the baseline and the candidate, so a drift of the machine affects both; the
 *       cost of an endpoint is the median of its rounds;</li>
 *   <li>{@link Judge judges} each candidate against the baseline.</li>
 * </ol>
 * Database-side findings the agent cannot see ({@code MISSING_INDEX}) are reproduced on each run's database from
 * the plan of their statement ({@code EXPLAIN (GENERIC_PLAN)}), and their plans before / after are reported. When
 * a patch changes the schema, an endpoint running the same SQL whose plans did not change is not held
 * responsible for a slower measurement.
 */
public final class Prover {

    /**
     * @param workspace where the copies are built and how
     * @param app       how the application is started
     * @param requests  replayed on every run
     * @param seed      loads the data once the schema exists
     * @param rounds    measured passes over every request, per candidate (alternated with the baseline)
     * @param image     PostgreSQL image
     * @param logs      application logs
     * @param keep      keep the copies (and their builds) after the run
     */
    public record Config(Workspace.Config workspace, AppRun.Options app, List<RequestSpec> requests, Seeder seed,
                         int rounds, String image, Path logs, Judge.Thresholds thresholds, boolean keep) { }

    static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final Consumer<String> log;

    public Prover(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    /**
     * A patch and the findings it is meant to fix (several when the same change fixes the same cause seen on
     * several endpoints: {@code Book.publisher} EAGER on {@code GET /books} and {@code GET /authors}).
     */
    public record Candidate(List<String> findings, Path patch) {
        public Candidate {
            if (findings.isEmpty()) throw new IllegalArgumentException("a candidate fixes at least one finding");
            findings = List.copyOf(findings);
        }
    }

    /** @return one verdict per patch, in order */
    public List<Verdict> prove(String findingId, List<Path> patches) throws Exception {
        return prove(patches.stream().map(p -> new Candidate(List.of(findingId), p)).toList(), List.of());
    }

    /**
     * @param candidates       patches to prove, each against the same baseline
     * @param databaseFindings bundles of the database-side findings involved ({@code MISSING_INDEX}): the agent
     *                         does not see them, they are reproduced from the plan of their statement
     * @return one verdict per candidate, in order. With several findings, the verdict of the first one the patch
     *         is accepted for (else the first rejection), every per-finding outcome listed in its report
     */
    public List<Verdict> prove(List<Candidate> candidates, List<JsonNode> databaseFindings) throws Exception {
        List<Plans.MissingIndex> probes = new ArrayList<>();
        for (JsonNode b : databaseFindings) {
            if (b.path("kind").asText().equals("MISSING_INDEX")) probes.add(Plans.MissingIndex.of(b));
        }
        Workspace ws = new Workspace(config.workspace());
        try {
            log.accept("building the baseline");
            Path baseJar;
            try {
                baseJar = ws.prepare("baseline", null);
            } catch (Workspace.Failure f) {
                throw new IllegalStateException("the application does not build without any patch: " + f.getMessage());
            }
            Map<Integer, Path> jars = new LinkedHashMap<>();
            Map<Integer, Verdict> verdicts = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                Path patch = candidates.get(i).patch();
                log.accept("applying and building " + patch);
                try {
                    jars.put(i, ws.prepare("candidate-" + (i + 1), patch));
                } catch (Workspace.Failure f) {
                    verdicts.put(i, failed(candidates.get(i).findings().get(0), patch, "patch " + f.getMessage()));
                }
            }

            try (ProofDatabase db = ProofDatabase.start(config.image())) {
                log.accept("creating the schema and seeding the template database");
                // the application runs its migrations, then is stopped before seeding and cloning
                start("bootstrap", baseJar, false, db, ProofDatabase.TEMPLATE).close();
                try (Connection c = db.connect(ProofDatabase.TEMPLATE)) {
                    config.seed().seed(c);
                }
                db.analyzeTemplate();

                log.accept("starting the baseline");
                try (Run baseline = open("baseline", baseJar, db, probes)) {
                    Map<String, JsonNode> reported = baseline.findings();
                    for (Candidate c : candidates) {
                        for (String id : c.findings()) {
                            if (!reported.containsKey(id)) {
                                log.accept("finding " + id + " not reported without the fix; reported: " + reported.keySet());
                            }
                        }
                    }
                    for (Map.Entry<Integer, Path> e : jars.entrySet()) {
                        Candidate cand = candidates.get(e.getKey());
                        String name = "candidate-" + (e.getKey() + 1);
                        log.accept("measuring " + name + " (" + cand.patch().getFileName() + ") against the baseline, "
                                + config.rounds() + " alternated rounds");
                        Verdict v;
                        try (Run candidate = open(name, e.getValue(), db, probes)) {
                            int from = baseline.snapshots.size() - 1;
                            for (int round = 0; round < config.rounds(); round++) {
                                baseline.round();
                                candidate.round();
                            }
                            Measurement before = baseline.measurement(from);
                            Measurement after = candidate.measurement(0);
                            boolean schemaChanged = ws.changesSchema(name);
                            Set<String> samePlans = schemaChanged ? samePlans(before, after, baseline, candidate) : Set.of();
                            v = judge(cand, before, after, schemaChanged, samePlans);
                            plans(v.report(), probes, cand, baseline, candidate);
                        } catch (IllegalStateException ex) {
                            v = failed(cand.findings().get(0), cand.patch(), "the application " + ex.getMessage());
                        }
                        verdicts.put(e.getKey(), v);
                    }
                }
            }
            List<Verdict> out = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                Verdict v = verdicts.get(i);
                v.report().put("patch", candidates.get(i).patch().toString());
                v.report().put("rounds", config.rounds());
                out.add(v);
            }
            return out;
        } finally {
            if (!config.keep()) ws.cleanup();
        }
    }

    /** Judges the patch for each of its findings; keeps the first acceptance, else the first rejection. */
    private Verdict judge(Candidate c, Measurement before, Measurement after, boolean schemaChanged,
                          Set<String> samePlans) {
        List<Verdict> each = new ArrayList<>();
        for (String id : c.findings()) {
            each.add(Judge.judge(id, before, after, config.thresholds(), schemaChanged, samePlans));
        }
        Verdict chosen = each.stream().filter(Verdict::accepted).findFirst()
                .or(() -> each.stream().filter(v -> v.status() == Verdict.Status.REJECTED).findFirst())
                .orElse(each.get(0));
        if (c.findings().size() > 1) {
            ArrayNode all = chosen.report().putArray("findings");
            for (int i = 0; i < each.size(); i++) {
                ObjectNode f = all.addObject();
                f.put("id", c.findings().get(i));
                f.put("status", each.get(i).status().name());
                f.set("reasons", JSON.valueToTree(each.get(i).reasons()));
            }
        }
        return chosen;
    }

    /**
     * Endpoints that run the same SQL with and without the patch and whose every statement keeps the same
     * plan shape: their time difference cannot come from the patch.
     */
    static Set<String> samePlans(Measurement before, Measurement after, Run baseline, Run candidate) throws Exception {
        Set<String> out = new TreeSet<>();
        try (Connection b = baseline.db.connectForPlans(baseline.database);
             Connection a = candidate.db.connectForPlans(candidate.database)) {
            for (Map.Entry<String, Measurement.EndpointCost> e : before.endpoints().entrySet()) {
                Measurement.EndpointCost x = e.getValue();
                Measurement.EndpointCost y = after.endpoints().get(e.getKey());
                if (y == null || x.statements().isEmpty() || !x.statements().equals(y.statements())) continue;
                boolean same = true;
                for (String st : x.statements()) {
                    String sql = st.substring(st.indexOf(" x ") + 3);
                    Optional<String> p1 = Plans.explain(b, sql).map(Plans::shape);
                    Optional<String> p2 = Plans.explain(a, sql).map(Plans::shape);
                    if (p1.isEmpty() || !p1.equals(p2)) {
                        same = false;
                        break;
                    }
                }
                if (same) out.add(e.getKey());
            }
        }
        return out;
    }

    /** Plan of each database finding's statement, before and after the patch, in the report. */
    private static void plans(ObjectNode report, List<Plans.MissingIndex> probes, Candidate c, Run baseline,
                              Run candidate) throws Exception {
        List<Plans.MissingIndex> mine = probes.stream().filter(p -> c.findings().contains(p.id())).toList();
        if (mine.isEmpty()) return;
        ArrayNode out = report.putArray("plans");
        try (Connection b = baseline.db.connectForPlans(baseline.database);
             Connection a = candidate.db.connectForPlans(candidate.database)) {
            for (Plans.MissingIndex p : mine) {
                out.addObject().put("finding", p.id()).put("sql", p.sql())
                        .put("before", p.plan(b)).put("after", p.plan(a));
            }
        }
    }

    private AppRun start(String name, Path jar, boolean agent, ProofDatabase db, String database) throws Exception {
        return AppRun.start(name, jar, agent, config.app(), db.jdbcUrl(database), ProofDatabase.USER,
                ProofDatabase.PASSWORD, config.logs());
    }

    /** Clones the template, starts the application with the agent, replays every request once. */
    private Run open(String name, Path jar, ProofDatabase db, List<Plans.MissingIndex> probes) throws Exception {
        String database = "fq_" + name.replace('-', '_');
        db.cloneTemplate(database);
        AppRun app;
        try {
            app = start(name, jar, true, db, database);
        } catch (Exception | Error e) {
            db.drop(database);
            throw e;
        }
        Run run = new Run(app, db, database, config.requests(), probes);
        try {
            run.firstPass();
        } catch (Exception | Error e) {
            run.close();
            throw e;
        }
        return run;
    }

    /**
     * One running application: the responses of its first pass (also its warm-up) and a snapshot of
     * {@code /fq/profile} after every pass, so the cost of each round is known separately.
     */
    static final class Run implements AutoCloseable {

        final AppRun app;
        final ProofDatabase db;
        final String database;
        final List<RequestSpec> requests;
        final List<Plans.MissingIndex> probes;
        final Map<String, Measurement.Response> responses = new LinkedHashMap<>();
        final List<JsonNode> snapshots = new ArrayList<>();

        Run(AppRun app, ProofDatabase db, String database, List<RequestSpec> requests, List<Plans.MissingIndex> probes) {
            this.app = app;
            this.db = db;
            this.database = database;
            this.requests = requests;
            this.probes = probes;
        }

        void firstPass() throws Exception {
            for (RequestSpec r : requests) {
                HttpResponse<String> res = app.get(r.path());
                responses.put(r.path(), new Measurement.Response(res.statusCode(), r.compareBody() ? json(res.body()) : null));
            }
            snapshots.add(app.agent("/fq/profile"));
        }

        void round() throws Exception {
            for (RequestSpec r : requests) app.get(r.path());
            snapshots.add(app.agent("/fq/profile"));
        }

        Map<String, JsonNode> findings() throws Exception {
            Map<String, JsonNode> findings = new LinkedHashMap<>();
            for (JsonNode f : app.agent("/fq/findings").path("findings")) findings.put(BundleBuilder.findingId(f), f);
            if (!probes.isEmpty()) {
                try (Connection c = db.connectForPlans(database)) {
                    for (Plans.MissingIndex p : probes) p.check(c).ifPresent(f -> findings.put(p.id(), f));
                }
            }
            return findings;
        }

        /** Rounds after snapshot {@code from}: median cost per request of each endpoint. */
        Measurement measurement(int from) throws Exception {
            return new Measurement(responses, medianCosts(snapshots.subList(from, snapshots.size())), findings());
        }

        @Override
        public void close() throws Exception {
            try {
                app.close();
            } finally {
                db.drop(database);
            }
        }
    }

    /**
     * Median over the rounds (one round = between two consecutive snapshots) of each endpoint's cost per
     * request: a single slow round (checkpoint, autovacuum, a disk spill) does not decide a verdict.
     */
    static Map<String, Measurement.EndpointCost> medianCosts(List<JsonNode> snapshots) {
        Map<String, List<Measurement.EndpointCost>> rounds = new LinkedHashMap<>();
        for (int i = 1; i < snapshots.size(); i++) {
            costs(snapshots.get(i - 1), snapshots.get(i))
                    .forEach((endpoint, c) -> rounds.computeIfAbsent(endpoint, k -> new ArrayList<>()).add(c));
        }
        Map<String, Measurement.EndpointCost> out = new LinkedHashMap<>();
        rounds.forEach((endpoint, list) -> out.put(endpoint, new Measurement.EndpointCost(
                list.stream().mapToLong(Measurement.EndpointCost::requests).sum(),
                median(list.stream().mapToDouble(Measurement.EndpointCost::statementsPerRequest).toArray()),
                median(list.stream().mapToDouble(Measurement.EndpointCost::sqlMsPerRequest).toArray()),
                list.get(list.size() - 1).statements())));
        return out;
    }

    /** Sorted {@code "<per request> x <sql>"} of an endpoint profile. */
    static List<String> statements(JsonNode profile) {
        List<String> out = new ArrayList<>();
        for (JsonNode st : profile.path("statements")) {
            out.add(String.format(java.util.Locale.ROOT, "%.2f x %s", st.path("perRequest").asDouble(), st.path("sql").asText()));
        }
        out.sort(null);
        return List.copyOf(out);
    }

    static double median(double[] values) {
        double[] v = values.clone();
        java.util.Arrays.sort(v);
        int n = v.length;
        return n == 0 ? 0 : n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }

    /** Cost per request over the measured rounds only: difference between two snapshots of /fq/profile. */
    static Map<String, Measurement.EndpointCost> costs(JsonNode warm, JsonNode measured) {
        Map<String, JsonNode> before = new HashMap<>();
        for (JsonNode p : warm.path("profiles")) before.put(p.path("endpoint").asText(), p);
        Map<String, Measurement.EndpointCost> out = new LinkedHashMap<>();
        for (JsonNode p : measured.path("profiles")) {
            String endpoint = p.path("endpoint").asText();
            JsonNode b = before.get(endpoint);
            long r1 = p.path("profiledRequests").asLong();
            long r0 = b == null ? 0 : b.path("profiledRequests").asLong();
            long n = r1 - r0;
            if (n <= 0) continue;
            double q = (p.path("queriesPerRequest").asDouble() * r1
                    - (b == null ? 0 : b.path("queriesPerRequest").asDouble() * r0)) / n;
            double t = (p.path("sqlTimeMsPerRequest").asDouble() * r1
                    - (b == null ? 0 : b.path("sqlTimeMsPerRequest").asDouble() * r0)) / n;
            out.put(endpoint, new Measurement.EndpointCost(n, Math.max(0, q), Math.max(0, t), statements(p)));
        }
        return out;
    }

    private static JsonNode json(String body) {
        try {
            return body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static Verdict failed(String findingId, Path patch, String why) {
        ObjectNode report = JSON.createObjectNode();
        report.put("finding", findingId);
        report.put("patch", patch.toString());
        report.put("status", Verdict.Status.REJECTED.name());
        report.putArray("reasons").add(why);
        return new Verdict(Verdict.Status.REJECTED, List.of(why), report);
    }
}
