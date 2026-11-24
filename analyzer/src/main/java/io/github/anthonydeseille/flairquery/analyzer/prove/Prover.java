package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    record Config(Workspace.Config workspace, AppRun.Options app, List<RequestSpec> requests, Seeder seed,
                         int rounds, String image, Path logs, Judge.Thresholds thresholds, boolean keep) { }

    static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final Consumer<String> log;

    public Prover(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    /** @return one verdict per patch, in order */
    public List<Verdict> prove(String findingId, List<Path> patches) throws Exception {
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
            for (int i = 0; i < patches.size(); i++) {
                log.accept("applying and building " + patches.get(i));
                try {
                    jars.put(i, ws.prepare("candidate-" + (i + 1), patches.get(i)));
                } catch (Workspace.Failure f) {
                    verdicts.put(i, failed(findingId, patches.get(i), "patch " + f.getMessage()));
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
                try (Run baseline = open("baseline", baseJar, db)) {
                    if (!baseline.findings().containsKey(findingId)) {
                        log.accept("finding " + findingId + " not reported without the fix; reported: "
                                + baseline.findings().keySet());
                    }
                    for (Map.Entry<Integer, Path> e : jars.entrySet()) {
                        Path patch = patches.get(e.getKey());
                        String name = "candidate-" + (e.getKey() + 1);
                        log.accept("measuring " + name + " (" + patch.getFileName() + ") against the baseline, "
                                + config.rounds() + " alternated rounds");
                        Verdict v;
                        try (Run candidate = open(name, e.getValue(), db)) {
                            int from = baseline.snapshots.size() - 1;
                            for (int round = 0; round < config.rounds(); round++) {
                                baseline.round();
                                candidate.round();
                            }
                            v = Judge.judge(findingId, baseline.measurement(from), candidate.measurement(0),
                                    config.thresholds(), ws.changesSchema(name));
                        } catch (IllegalStateException ex) {
                            v = failed(findingId, patch, "the application " + ex.getMessage());
                        }
                        verdicts.put(e.getKey(), v);
                    }
                }
            }
            List<Verdict> out = new ArrayList<>();
            for (int i = 0; i < patches.size(); i++) {
                Verdict v = verdicts.get(i);
                v.report().put("patch", patches.get(i).toString());
                v.report().put("rounds", config.rounds());
                out.add(v);
            }
            return out;
        } finally {
            if (!config.keep()) ws.cleanup();
        }
    }

    private AppRun start(String name, Path jar, boolean agent, ProofDatabase db, String database) throws Exception {
        return AppRun.start(name, jar, agent, config.app(), db.jdbcUrl(database), ProofDatabase.USER,
                ProofDatabase.PASSWORD, config.logs());
    }

    /** Clones the template, starts the application with the agent, replays every request once. */
    private Run open(String name, Path jar, ProofDatabase db) throws Exception {
        String database = "fq_" + name.replace('-', '_');
        db.cloneTemplate(database);
        AppRun app;
        try {
            app = start(name, jar, true, db, database);
        } catch (Exception | Error e) {
            db.drop(database);
            throw e;
        }
        Run run = new Run(app, db, database, config.requests());
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
    private static final class Run implements AutoCloseable {

        final AppRun app;
        final ProofDatabase db;
        final String database;
        final List<RequestSpec> requests;
        final Map<String, Measurement.Response> responses = new LinkedHashMap<>();
        final List<JsonNode> snapshots = new ArrayList<>();

        Run(AppRun app, ProofDatabase db, String database, List<RequestSpec> requests) {
            this.app = app;
            this.db = db;
            this.database = database;
            this.requests = requests;
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
