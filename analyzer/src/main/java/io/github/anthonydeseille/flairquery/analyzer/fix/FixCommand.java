package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.llm.ClaudeClient;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
import io.github.anthonydeseille.flairquery.analyzer.prove.Prover;
import io.github.anthonydeseille.flairquery.analyzer.prove.RequestSpec;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * {@code fix}: generates a fix for every bundle, writes one patch per distinct change (the same change fixing the
 * same cause on several endpoints is one patch), then proves them all against one baseline. Only an ACCEPTED fix
 * is proposed ({@code "proposed": true} in {@code index.json}).
 *
 * <p>Deterministic templates first; the LLM generator for the findings no template handles, for the ones a
 * template skips, and for every fix the prover rejects: it is asked again with the rejected patches and the
 * prover's reasons, then the new fixes are proven, up to {@code --llm-retries} times. {@code --llm off}: templates
 * only.</p>
 *
 * <pre>
 *   fix --bundles target/analysis [--finding ID[,ID...]] [--repo .] [--app demo-app] [--out target/fixes]
 *       [--prove true] [--llm claude|off] [--model claude-opus-5-5] [--effort high] [--llm-retries 2]
 *       [--llm-turns 20] [prover options, see {@link ProveCommand}]
 * </pre>
 * On the demo app, the trap scorecard ({@link TrapScorecard}) is printed and written to {@code scorecard.md}.
 * Exit code 0 when every finding that got a fix ends with a proven one (or {@code --prove false}), 1 otherwise.
 */
public final class FixCommand {

    public static final String USAGE = """
            usage: fix --bundles DIR [--finding ID[,ID...]] [--repo .] [--app demo-app] [--out target/fixes]
                       [--prove true] [--llm claude|off] [--model claude-opus-5-5] [--effort high]
                       [--llm-retries 2] [--llm-turns 20] [prove options]""";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final int LLM_PARALLELISM = 4;

    /** The deterministic generators (templates), tried before the LLM. */
    public static List<FixGenerator> generators() {
        return List.of(new LazyAssociationFix(), new IndexMigrationFix());
    }

    /**
     * One distinct change and every finding it is generated for.
     *
     * @param attempt  1 for a first fix, n for the (n-1)th retry of the same findings
     * @param rejected the earlier fixes of these findings the prover rejected, oldest first
     * @param replaces patch name of the rejected fix this one replaces, null for a first fix
     */
    public record Proposal(Fix fix, String patch, List<JsonNode> bundles, int attempt,
                           List<FixGenerator.Attempt> rejected, String replaces) {
        public Proposal(Fix fix, String patch, List<JsonNode> bundles) {
            this(fix, patch, bundles, 1, List.of(), null);
        }

        public List<String> findings() {
            return bundles.stream().map(b -> b.path("id").asText()).toList();
        }
    }

    /** A proposal, its patch file and its verdict (null when not proven). */
    public record Outcome(Proposal proposal, Path patch, Verdict verdict) {
        public String name() {
            return patch.getFileName().toString().replaceAll("\\.patch$", "");
        }
    }

    /** Proves proposals against one baseline (the real prover, or a stand-in in unit tests). */
    public interface Proof {
        List<Verdict> prove(List<Proposal> proposals, List<Path> patches) throws Exception;
    }

    private FixCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("bundles")) {
            System.err.println(USAGE);
            return 2;
        }
        Path repo = ProveCommand.repo(opt);
        String app = opt.getOrDefault("app", "demo-app");
        Path out = Path.of(opt.getOrDefault("out", repo.resolve("target/fixes").toString())).toAbsolutePath();
        Set<String> only = opt.containsKey("finding")
                ? new HashSet<>(Arrays.stream(opt.get("finding").split(",")).map(String::strip).toList()) : null;

        Proof proof = null;
        if (Boolean.parseBoolean(opt.getOrDefault("prove", "true"))) {
            Prover.Config config = ProveCommand.config(opt);
            if (config == null) return 2;
            proof = (proposals, patches) -> prove(config, proposals, patches);
        }
        LlmFixGenerator llm = null;
        if (!opt.getOrDefault("llm", "claude").equals("off")) {
            if (!opt.getOrDefault("llm", "claude").equals("claude")) {
                System.err.println("--llm: claude or off");
                return 2;
            }
            Path requests = Path.of(opt.getOrDefault("requests", repo.resolve("analyzer/requests-demo.txt").toString()));
            llm = new LlmFixGenerator(new ClaudeClient(opt.get("model"), opt.getOrDefault("effort", "high"), 64_000),
                    describe(RequestSpec.read(requests)), Integer.parseInt(opt.getOrDefault("llm-turns", "20")),
                    System.out::println);
            System.out.println("LLM fixes: " + llm.model() + " (the evidence and the application's source files it "
                    + "reads are sent to the Claude API; --llm off for templates only)");
        }

        List<JsonNode> bundles = load(Path.of(opt.get("bundles")), only);
        Path appDir = repo.resolve(app);
        List<Proposal> proposals = generate(bundles, appDir, app, generators(), llm, System.out::println);
        if (proposals.isEmpty()) {
            System.out.println("no fix generated");
            return 0;
        }
        List<Outcome> outcomes = proveAndRetry(proposals, appDir, app, llm,
                Integer.parseInt(opt.getOrDefault("llm-retries", "2")), proof, out, System.out::println);
        writeIndex(outcomes, out);
        Set<String> analyzed = new HashSet<>(bundles.stream().map(b -> b.path("id").asText()).toList());
        if (proof != null && TrapScorecard.isDemo(analyzed)) {
            List<TrapScorecard.Row> rows = TrapScorecard.of(outcomes, analyzed);
            System.out.print(TrapScorecard.text(rows));
            Files.writeString(out.resolve("scorecard.md"), TrapScorecard.markdown(rows));
        }
        if (llm != null) {
            LlmClient.Usage u = llm.usage();
            System.out.printf("LLM tokens: %,d input (%,d read from cache, %,d written to it), %,d output%n",
                    u.inputTokens() + u.cacheReadTokens() + u.cacheWriteTokens(), u.cacheReadTokens(),
                    u.cacheWriteTokens(), u.outputTokens());
        }
        return proof == null || finals(outcomes).stream().allMatch(o -> o.verdict().accepted()) ? 0 : 1;
    }

    /** The requests as the model reads them: path, and whether only the status is compared. */
    static List<String> describe(List<RequestSpec> requests) {
        return requests.stream().map(r -> "GET " + r.path() + (r.compareBody() ? "" : "  (status only)")).toList();
    }

    /** The bundles listed in {@code index.json}, optionally only some finding ids. */
    static List<JsonNode> load(Path dir, Set<String> only) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode i : JSON.readTree(dir.resolve("index.json").toFile())) {
            String id = i.path("id").asText();
            if (only == null || only.contains(id)) out.add(JSON.readTree(dir.resolve(id + ".json").toFile()));
        }
        return out;
    }

    /** Templates only (no LLM). */
    public static List<Proposal> generate(List<JsonNode> bundles, Path appDir, String appPrefix,
                                          List<FixGenerator> templates, Consumer<String> log) throws Exception {
        return generate(bundles, appDir, appPrefix, templates, null, log);
    }

    /**
     * A fix per bundle, from the first template that handles it, else (or when the template skips it) from the
     * LLM generator when there is one. Identical patches are one proposal listing all its findings.
     */
    public static List<Proposal> generate(List<JsonNode> bundles, Path appDir, String appPrefix,
                                          List<FixGenerator> templates, FixGenerator llm, Consumer<String> log)
            throws Exception {
        List<Callable<FixGenerator.Result>> tasks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JsonNode b : bundles) {
            FixGenerator t = templates.stream().filter(x -> x.handles(b)).findFirst().orElse(null);
            FixGenerator.Result r = t == null ? null : t.generate(b, appDir);
            if (r != null && r.fix() != null) {
                tasks.add(() -> r);
                skipped.add(null);
                continue;
            }
            if (llm != null && llm.handles(b)) {
                tasks.add(() -> llm.generate(b, appDir));
                skipped.add(r == null ? null : t.name() + ": " + r.skipped());
            } else {
                tasks.add(null);
                skipped.add(r == null ? "no-generator" : r.skipped());
            }
        }
        List<FixGenerator.Result> results = parallel(tasks);

        Map<String, Proposal> byPatch = new LinkedHashMap<>();
        for (int i = 0; i < bundles.size(); i++) {
            JsonNode b = bundles.get(i);
            String id = b.path("id").asText();
            FixGenerator.Result r = results.get(i);
            if (r == null) {
                log.accept(skipped.get(i).equals("no-generator")
                        ? String.format("%-12s %-32s %s", "no-generator", id, b.path("kind").asText())
                        : String.format("%-12s %-32s %s", "skipped", id, skipped.get(i)));
                continue;
            }
            if (skipped.get(i) != null) log.accept(String.format("%-12s %-32s %s -> llm", "skipped", id, skipped.get(i)));
            if (r.fix() == null) {
                log.accept(String.format("%-12s %-32s %s", "skipped", id, r.skipped()));
                continue;
            }
            String patch = r.fix().patch(appPrefix);
            Proposal p = byPatch.get(patch);
            if (p == null) {
                byPatch.put(patch, new Proposal(r.fix(), patch, new ArrayList<>(List.of(b))));
            } else {
                p.bundles().add(b);
            }
            log.accept(String.format("%-12s %-32s %s", "generated", id, r.fix().name()));
        }
        return new ArrayList<>(byPatch.values());
    }

    /**
     * Proves the proposals; every REJECTED one gets another LLM fix built from the rejected patches and the
     * prover's reasons, and the new fixes are proven in turn, up to {@code retries} times. Without a proof, the
     * patches are only written.
     */
    public static List<Outcome> proveAndRetry(List<Proposal> proposals, Path appDir, String appPrefix,
                                              FixGenerator llm, int retries, Proof proof, Path out,
                                              Consumer<String> log) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Proposal> pending = proposals;
        for (int round = 0; !pending.isEmpty(); round++) {
            List<Path> patches = writePatches(pending, out, names);
            List<Verdict> verdicts = proof == null ? null : proof.prove(pending, patches);
            List<Callable<FixGenerator.Result>> tasks = new ArrayList<>();
            List<Proposal> retried = new ArrayList<>();
            List<List<FixGenerator.Attempt>> histories = new ArrayList<>();
            List<String> replaced = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                Proposal p = pending.get(i);
                Verdict v = verdicts == null ? null : verdicts.get(i);
                Outcome o = new Outcome(p, patches.get(i), v);
                outcomes.add(o);
                if (v == null || v.status() != Verdict.Status.REJECTED || llm == null || round >= retries) continue;
                List<JsonNode> bundles = p.bundles().stream().filter(llm::handles).toList();
                if (bundles.isEmpty()) continue;
                List<FixGenerator.Attempt> history = new ArrayList<>(p.rejected());
                history.add(new FixGenerator.Attempt(p.fix(), p.patch(), v.reasons()));
                tasks.add(() -> llm.retry(bundles, appDir, history));
                retried.add(p);
                histories.add(history);
                replaced.add(o.name());
            }
            List<FixGenerator.Result> results = parallel(tasks);
            Map<String, Proposal> next = new LinkedHashMap<>();
            for (int i = 0; i < results.size(); i++) {
                Proposal p = retried.get(i);
                FixGenerator.Result r = results.get(i);
                if (r.fix() == null) {
                    log.accept(String.format("%-12s %-32s %s", "no-retry", replaced.get(i), r.skipped()));
                    continue;
                }
                String patch = r.fix().patch(appPrefix);
                if (histories.get(i).stream().anyMatch(a -> a.patch().equals(patch))) {
                    log.accept(String.format("%-12s %-32s %s", "no-retry", replaced.get(i), "the same patch again"));
                    continue;
                }
                Proposal q = next.get(patch);
                if (q == null) {
                    next.put(patch, new Proposal(r.fix(), patch, new ArrayList<>(p.bundles()), p.attempt() + 1,
                            histories.get(i), replaced.get(i)));
                } else {
                    q.bundles().addAll(p.bundles());
                }
                log.accept(String.format("%-12s %-32s %s (attempt %d)", "retry", replaced.get(i), r.fix().name(),
                        p.attempt() + 1));
            }
            pending = new ArrayList<>(next.values());
        }
        return outcomes;
    }

    /** The last outcome of each chain of attempts (the ones no retry replaced). */
    static List<Outcome> finals(List<Outcome> outcomes) {
        Set<String> replaced = new HashSet<>();
        outcomes.forEach(o -> {
            if (o.proposal().replaces() != null) replaced.add(o.proposal().replaces());
        });
        return outcomes.stream().filter(o -> !replaced.contains(o.name())).toList();
    }

    /** Runs the tasks (null = no result) a few at a time, results in task order. */
    static List<FixGenerator.Result> parallel(List<Callable<FixGenerator.Result>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(LLM_PARALLELISM);
        try {
            List<Future<FixGenerator.Result>> futures = new ArrayList<>();
            for (Callable<FixGenerator.Result> t : tasks) futures.add(t == null ? null : pool.submit(t));
            List<FixGenerator.Result> out = new ArrayList<>();
            for (Future<FixGenerator.Result> f : futures) {
                if (f == null) {
                    out.add(null);
                    continue;
                }
                try {
                    out.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    out.add(FixGenerator.Result.skip("generator failed: " + e.getCause()));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Path> writePatches(List<Proposal> proposals, Path out) throws Exception {
        return writePatches(proposals, out, new HashSet<>());
    }

    /** One {@code <name>.patch} per proposal, names unique among {@code names} (updated). */
    static List<Path> writePatches(List<Proposal> proposals, Path out, Set<String> names) throws Exception {
        Files.createDirectories(out);
        List<Path> patches = new ArrayList<>();
        for (Proposal p : proposals) {
            String name = p.fix().name();
            for (int n = 2; !names.add(name); n++) name = p.fix().name() + "-" + n;
            Path file = out.resolve(name + ".patch");
            Files.writeString(file, p.patch());
            patches.add(file);
        }
        return patches;
    }

    /** Every proposal against one baseline; database findings are reproduced from their bundles. */
    public static List<Verdict> prove(Prover.Config config, List<Proposal> proposals, List<Path> patches) throws Exception {
        List<Prover.Candidate> candidates = new ArrayList<>();
        List<JsonNode> database = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < proposals.size(); i++) {
            candidates.add(new Prover.Candidate(proposals.get(i).findings(), patches.get(i)));
            for (JsonNode b : proposals.get(i).bundles()) {
                if (b.path("origin").asText().equals("collector") && seen.add(b.path("id").asText())) database.add(b);
            }
        }
        return new Prover(config, m -> System.out.println("[prove] " + m)).prove(candidates, database);
    }

    static void writeIndex(List<Outcome> outcomes, Path out) throws Exception {
        Path proofs = out.resolve("proofs");
        ArrayNode index = JSON.createArrayNode();
        for (Outcome o : outcomes) {
            Proposal p = o.proposal();
            String name = o.name();
            ObjectNode e = index.addObject();
            e.put("name", name);
            e.put("generator", p.fix().generator());
            e.put("description", p.fix().description());
            ArrayNode f = e.putArray("findings");
            p.findings().forEach(f::add);
            e.put("attempt", p.attempt());
            if (p.replaces() != null) e.put("replaces", p.replaces());
            e.put("patch", o.patch().toString());
            Verdict v = o.verdict();
            e.put("status", v == null ? "UNPROVEN" : v.status().name());
            e.put("proposed", v != null && v.accepted());
            if (v != null) {
                e.set("reasons", JSON.valueToTree(v.reasons()));
                ArrayNode resolved = e.putArray("resolves");
                v.report().path("findingsResolved").forEach(r -> resolved.add(r.path("id").asText()));
                Files.createDirectories(proofs);
                Path report = proofs.resolve(name + ".json");
                JSON.writeValue(report.toFile(), v.report());
                e.put("report", report.toString());
            }
            System.out.printf("%-12s %-36s %s%n", e.path("status").asText(), name, p.fix().description());
            if (v != null) v.reasons().forEach(r -> System.out.println("             - " + r));
        }
        Files.createDirectories(out);
        JSON.writeValue(out.resolve("index.json").toFile(), index);
        long proposed = outcomes.stream().filter(o -> o.verdict() != null && o.verdict().accepted()).count();
        System.out.println(outcomes.size() + " fix(es), " + proposed + " proven, index written to " + out.resolve("index.json"));
    }
}
