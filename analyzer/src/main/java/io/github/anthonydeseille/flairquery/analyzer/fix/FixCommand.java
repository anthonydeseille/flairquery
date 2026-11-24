package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
import io.github.anthonydeseille.flairquery.analyzer.prove.Prover;
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

/**
 * {@code fix}: generates a fix for every bundle a generator handles, writes one patch per distinct change (the
 * same change fixing the same cause on several endpoints is one patch), then proves them all against one
 * baseline. Only an ACCEPTED fix is proposed ({@code "proposed": true} in {@code index.json}).
 *
 * <pre>
 *   fix --bundles target/analysis [--finding ID[,ID...]] [--repo .] [--app demo-app] [--out target/fixes]
 *       [--prove true] [prover options, see {@link ProveCommand}]
 * </pre>
 * Exit code 0 when every generated fix is proven (or {@code --prove false}), 1 otherwise.
 */
public final class FixCommand {

    public static final String USAGE = """
            usage: fix --bundles DIR [--finding ID[,ID...]] [--repo .] [--app demo-app] [--out target/fixes]
                       [--prove true] [prove options]""";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The deterministic generators; the LLM one comes next (phase 4 step 4). */
    public static List<FixGenerator> generators() {
        return List.of(new LazyAssociationFix(), new IndexMigrationFix());
    }

    /** One distinct change and every finding it is generated for. */
    public record Proposal(Fix fix, String patch, List<JsonNode> bundles) {
        public List<String> findings() {
            return bundles.stream().map(b -> b.path("id").asText()).toList();
        }
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

        List<JsonNode> bundles = load(Path.of(opt.get("bundles")), only);
        List<Proposal> proposals = generate(bundles, repo.resolve(app), app, generators(), System.out::println);
        if (proposals.isEmpty()) {
            System.out.println("no fix generated");
            return 0;
        }
        List<Path> patches = writePatches(proposals, out);

        List<Verdict> verdicts = null;
        if (Boolean.parseBoolean(opt.getOrDefault("prove", "true"))) {
            Prover.Config config = ProveCommand.config(opt);
            if (config == null) return 2;
            verdicts = prove(config, proposals, patches);
        }
        writeIndex(proposals, patches, verdicts, out);
        return verdicts == null || verdicts.stream().allMatch(Verdict::accepted) ? 0 : 1;
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

    /** Generates and groups fixes: identical patches are one proposal listing all its findings. */
    public static List<Proposal> generate(List<JsonNode> bundles, Path appDir, String appPrefix,
                                          List<FixGenerator> generators, java.util.function.Consumer<String> log)
            throws Exception {
        Map<String, Proposal> byPatch = new LinkedHashMap<>();
        for (JsonNode b : bundles) {
            String id = b.path("id").asText();
            FixGenerator g = generators.stream().filter(x -> x.handles(b)).findFirst().orElse(null);
            if (g == null) {
                log.accept(String.format("%-12s %-32s %s", "no-generator", id, b.path("kind").asText()));
                continue;
            }
            FixGenerator.Result r = g.generate(b, appDir);
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

    static List<Path> writePatches(List<Proposal> proposals, Path out) throws Exception {
        Files.createDirectories(out);
        List<Path> patches = new ArrayList<>();
        Set<String> names = new HashSet<>();
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
        for (int i = 0; i < proposals.size(); i++) {
            candidates.add(new Prover.Candidate(proposals.get(i).findings(), patches.get(i)));
            for (JsonNode b : proposals.get(i).bundles()) {
                if (b.path("origin").asText().equals("collector")) database.add(b);
            }
        }
        return new Prover(config, m -> System.out.println("[prove] " + m)).prove(candidates, database);
    }

    static void writeIndex(List<Proposal> proposals, List<Path> patches, List<Verdict> verdicts, Path out) throws Exception {
        Path proofs = out.resolve("proofs");
        ArrayNode index = JSON.createArrayNode();
        for (int i = 0; i < proposals.size(); i++) {
            Proposal p = proposals.get(i);
            String name = patches.get(i).getFileName().toString().replaceAll("\\.patch$", "");
            ObjectNode e = index.addObject();
            e.put("name", name);
            e.put("generator", p.fix().generator());
            e.put("description", p.fix().description());
            ArrayNode f = e.putArray("findings");
            p.findings().forEach(f::add);
            e.put("patch", patches.get(i).toString());
            Verdict v = verdicts == null ? null : verdicts.get(i);
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
            System.out.printf("%-12s %-28s %s%n", e.path("status").asText(), name, p.fix().description());
            if (v != null) v.reasons().forEach(r -> System.out.println("             - " + r));
        }
        JSON.writeValue(out.resolve("index.json").toFile(), index);
        System.out.println(proposals.size() + " fix(es), index written to " + out.resolve("index.json"));
    }
}
