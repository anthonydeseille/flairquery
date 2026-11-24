package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public static final String USAGE = "usage: mr --fixes DIR [--bundles DIR] [--repo .] [--app demo-app] [--out target/mr]";

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

        List<Description.MergeRequest> mrs = plan(FixEntry.read(index), bundles, repo, app, out);
        System.out.println(mrs.size() + " merge request(s) written to " + out + " (dry run: nothing pushed)");
        return 0;
    }

    /** Selects the fixes and writes their merge requests (dry run); returns the selected ones. */
    public static List<Description.MergeRequest> plan(List<FixEntry> fixes, Map<String, JsonNode> bundles, Path repo,
                                                      String app, Path out) throws IOException, InterruptedException {
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
            Description.MergeRequest mr = Description.of(c.fix(), JSON.readTree(c.fix().report().toFile()), bundles, choices);
            Path dir = out.resolve(c.fix().name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("description.md"), mr.body());
            Files.copy(c.fix().patch(), dir.resolve(c.fix().name() + ".patch"), StandardCopyOption.REPLACE_EXISTING);
            e.put("branch", mr.branch()).put("title", mr.title()).put("description", dir.resolve("description.md").toString());
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
