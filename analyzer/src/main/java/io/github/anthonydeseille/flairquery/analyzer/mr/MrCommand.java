package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.fix.FixCommand;
import io.github.anthonydeseille.flairquery.analyzer.llm.ClaudeClient;
import io.github.anthonydeseille.flairquery.analyzer.prove.RequestSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code mr}: the merge requests of the proven fixes ({@code fix}'s output). Selects them ({@link Selection}),
 * writes for each its branch, title, description and patch under {@code --out}, and an {@code index.json} of
 * every decision (selected, redundant, deferred, not proven).
 */
public final class MrCommand {

    public static final String USAGE = """
            usage: mr --fixes DIR [--bundles DIR] [--repo .] [--app demo-app] [--out target/mr]
                      [--llm claude|off] [--model claude-opus-5-5] [--effort high] [--test-retries 2] [--llm-turns 20]
                      [--requests FILE] [--mvnw <repo>/mvnw]""";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MrCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("fixes")) {
            System.err.println(USAGE);
            return 2;
        }
        Path fixes = Path.of(opt.get("fixes"));
        Path index = fixes.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            System.err.println("no " + index + ": run fix first");
            return 2;
        }
        Path repo = Path.of(opt.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        String app = opt.getOrDefault("app", "demo-app");
        Path out = Path.of(opt.getOrDefault("out", "target/mr"));
        Map<String, JsonNode> bundles = opt.containsKey("bundles") ? bundles(Path.of(opt.get("bundles"))) : Map.of();

        LlmTestGenerator tests = null;
        String llm = opt.getOrDefault("llm", "claude");
        if (!llm.equals("off")) {
            if (!llm.equals("claude")) {
                System.err.println("--llm: claude or off");
                return 2;
            }
            Path requests = Path.of(opt.getOrDefault("requests", repo.resolve("analyzer/requests-demo.txt").toString()));
            tests = new LlmTestGenerator(new ClaudeClient(opt.get("model"), opt.getOrDefault("effort", "high"), 64_000),
                    FixCommand.describe(RequestSpec.read(requests)), Integer.parseInt(opt.getOrDefault("llm-turns", "20")),
                    System.out::println);
            System.out.println("regression tests: " + tests.model() + " (the fix, its proof and the application's source "
                    + "files it reads are sent to the Claude API; --llm off for merge requests without tests)");
        }
        TestProof proof = new TestProof.Maven(repo, app, out.toAbsolutePath().resolve("tests"),
                List.of(opt.getOrDefault("mvnw", repo.resolve("mvnw").toString())));
        List<Description.MergeRequest> mrs = plan(FixEntry.read(index), bundles, repo, app, out, tests, proof,
                Integer.parseInt(opt.getOrDefault("test-retries", "2")));
        if (tests != null) System.out.println("LLM usage (tests): " + tests.usage());
        System.out.println(mrs.size() + " merge request(s) written to " + out + " (dry run: nothing pushed)");
        return 0;
    }

    /** Without regression tests. */
    public static List<Description.MergeRequest> plan(List<FixEntry> fixes, Map<String, JsonNode> bundles, Path repo,
                                                      String app, Path out) throws Exception {
        return plan(fixes, bundles, repo, app, out, null, null, 0);
    }

    /**
     * Selects the fixes, writes a regression test proven red / green for each selected one (when {@code tests} is
     * not null) and writes their merge requests (dry run); returns the selected ones.
     */
    public static List<Description.MergeRequest> plan(List<FixEntry> fixes, Map<String, JsonNode> bundles, Path repo,
                                                      String app, Path out, RegressionTests.Generator tests,
                                                      TestProof proof, int retries) throws Exception {
        List<Selection.Choice> choices;
        try (GitStack stack = new GitStack(repo, app, out.toAbsolutePath().resolve("work"))) {
            choices = Selection.select(fixes, stack);
        }
        List<Description.MergeRequest> mrs = new ArrayList<>();
        ArrayNode all = JSON.createArrayNode();
        for (Selection.Choice c : choices) {
            ObjectNode e = all.addObject().put("fix", c.fix().name()).put("decision", c.decision().name());
            e.set("related", JSON.valueToTree(c.related()));
            e.put("why", c.why());
            System.out.printf("%-10s %-50s %s%n", c.decision(), c.fix().name(), c.why());
            if (c.decision() != Selection.Decision.SELECTED) continue;
            JsonNode report = JSON.readTree(c.fix().report().toFile());
            String patch = Files.readString(c.fix().patch());
            RegressionTests.Outcome test = tests == null ? null
                    : RegressionTests.of(c.fix(), patch, report, repo.resolve(app), tests, proof, retries, System.out::println);
            Description.MergeRequest mr = Description.of(c.fix(), report, bundles, choices, test);
            Path dir = out.resolve(c.fix().name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("description.md"), mr.body());
            String full = test == null || test.test() == null ? patch : patch + test.test().patch(app);
            Files.writeString(dir.resolve(c.fix().name() + ".patch"), full);
            e.put("branch", mr.branch()).put("title", mr.title()).put("description", dir.resolve("description.md").toString());
            if (test != null) {
                if (test.test() != null) {
                    e.put("test", test.test().path()).put("red", test.red());
                } else {
                    e.put("noTest", test.missing());
                }
                e.put("testAttempts", test.attempts());
            }
            mrs.add(mr);
            System.out.println("           -> " + mr.branch() + "  \"" + mr.title() + "\"");
        }
        Files.createDirectories(out);
        JSON.writeValue(out.resolve("index.json").toFile(), all);
        return mrs;
    }

    static Map<String, JsonNode> bundles(Path dir) throws IOException {
        Map<String, JsonNode> bundles = new HashMap<>();
        if (!Files.isDirectory(dir)) return bundles;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json") && !p.endsWith("index.json")).toList()) {
                JsonNode b = JSON.readTree(f.toFile());
                if (b.has("id")) bundles.put(b.path("id").asText(), b);
            }
        }
        return bundles;
    }
}
