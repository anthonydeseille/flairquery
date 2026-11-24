package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One fix of {@code fix}'s {@code index.json}: its patch, its verdict and the findings its proof resolved.
 *
 * @param findings the findings it was generated for
 * @param resolves the findings its proof showed gone (empty when not proven)
 * @param report   the proof report, null when not proven
 */
public record FixEntry(String name, String generator, String description, List<String> findings, int attempt,
                       Path patch, String status, boolean proposed, List<String> resolves, Path report) {

    public static FixEntry of(JsonNode e, Path base) {
        return new FixEntry(e.path("name").asText(), e.path("generator").asText(), e.path("description").asText(),
                strings(e.path("findings")), e.path("attempt").asInt(1), path(e.path("patch"), base),
                e.path("status").asText(), e.path("proposed").asBoolean(), strings(e.path("resolves")),
                path(e.path("report"), base));
    }

    public static List<FixEntry> read(Path index) throws IOException {
        JsonNode all = MrCommand.JSON.readTree(index.toFile());
        List<FixEntry> fixes = new ArrayList<>();
        for (JsonNode e : all) fixes.add(of(e, index.toAbsolutePath().getParent()));
        return fixes;
    }

    /** Written by a model rather than by a deterministic template. */
    public boolean llm() {
        return generator.equals("llm");
    }

    /** Files the patch touches (paths relative to the repository), from its {@code diff --git} headers. */
    public Set<String> files() throws IOException {
        return files(Files.readString(patch));
    }

    static Set<String> files(String patch) {
        Set<String> files = new LinkedHashSet<>();
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++ b/")) files.add(line.substring(6));
            else if (line.startsWith("--- a/")) files.add(line.substring(6));
        }
        return files;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    private static Path path(JsonNode n, Path base) {
        if (n.isMissingNode() || n.isNull() || n.asText().isEmpty()) return null;
        return base.resolve(n.asText());
    }
}
