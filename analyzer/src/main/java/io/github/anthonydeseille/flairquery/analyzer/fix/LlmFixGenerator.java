package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.code.SourceLocator;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Fixes written by a language model, for the findings no template handles (traps 1, 3, 5, 6: N+1, over-fetching,
 * JOIN FETCH + pagination, open-in-view) or whose template was skipped or rejected. The model gets the evidence
 * bundle, the source files it names, the list of the application's files (it reads more with {@code read_file})
 * and the rules of the proof; it answers with {@code propose_fix} (whole new content of every changed file) or
 * {@code no_fix}. After a rejection it is asked again with its earlier patches and the prover's reasons.
 *
 * <p>Nothing it writes is trusted: paths must stay inside the application, and the fix is only a candidate for
 * the prover, like a template's.</p>
 */
public final class LlmFixGenerator implements FixGenerator {

    /** Kinds handled; SORT_SPILL (trap 7) is out of phase 4: keyset pagination changes the API. */
    static final Set<String> KINDS = Set.of("N_PLUS_ONE_LAZY", "OVER_FETCHING", "IN_MEMORY_PAGINATION", "OPEN_IN_VIEW",
            "USELESS_EAGER", "MISSING_INDEX");
    static final int MAX_FILE_CHARS = 60_000;
    static final int MAX_LISTED_FILES = 400;
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static final String SYSTEM = """
            You fix one performance problem in the data access of a Java application (Spring Boot, Spring Data JPA,
            Hibernate 6). FlairQuery found it by observing the running application: you receive the finding, its
            evidence (what Hibernate did per request, the SQL statements with their callers and costs) and the source
            files involved. Propose the smallest change to the application that removes the problem.

            Your change is not trusted. It is applied to a copy of the application, built with Maven (tests skipped),
            started on a PostgreSQL database filled with test data with the FlairQuery agent attached, and the same
            HTTP requests are replayed with and without it. It reaches the developers only if all of this holds:
            - every response keeps its HTTP status and a strictly identical JSON body: same fields, same values, same
              order of array elements. Ordering is the usual trap: another query or fetch strategy may return rows in
              another order, so keep or make the order explicit (ORDER BY, Sort, @OrderBy) to match today's output;
            - the finding is no longer reported;
            - no new finding appears anywhere: N+1 lazy loading, EAGER association never read, over-fetching
              (entities loaded whose columns are never read), a collection JOIN FETCH combined with pagination
              (HHH90003004, pagination applied in memory), lazy loading outside a transaction kept alive by
              open-in-view;
            - the finding's endpoint runs fewer SQL statements and spends less SQL time per request (or the same
              statements with at least 20 % less SQL time);
            - no other endpoint runs more statements or spends noticeably more SQL time.

            Guidelines:
            - Fix the cause where the evidence points; URLs, parameters and JSON stay unchanged.
            - Prefer idiomatic Spring Data JPA: DTO or interface projections, @EntityGraph, JOIN FETCH, two steps
              (page the ids, then fetch the rows for those ids), @BatchSize, explicit loading in a
              @Transactional(readOnly = true) service. Native SQL only when JPQL cannot express it.
            - A change may touch several files and add new ones (a projection record, for instance). Keep each file's
              style (imports, indentation, javadoc); do not reformat what you do not change; keep comments truthful.
            - Do not change tests or the build. Change the database schema only for a database finding (a missing
              index), as a new migration of the project's migration tool.
            - The code must compile with the project's existing dependencies.
            - Read any other file you need with read_file before writing; the list of the application's files is
              given. Do not guess the content of a file you change: read it first if it was not given.
            - When ready, call propose_fix once with the complete new content of every file you change or add. If no
              safe fix exists, call no_fix with the reason.""";

    static final List<LlmClient.Tool> TOOLS = List.of(
            new LlmClient.Tool("read_file", "Returns the content of a file of the application, by its path relative "
                    + "to the application directory, as listed.", schema("""
                    {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}""")),
            new LlmClient.Tool("propose_fix", "Proposes the fix: the complete new content of every file changed or "
                    + "added (paths relative to the application directory). Call it once, when the fix is complete.",
                    schema("""
                    {"type": "object", "properties": {
                      "name": {"type": "string", "description": "short kebab-case name of the change, e.g. authors-entity-graph-books"},
                      "description": {"type": "string", "description": "one or two sentences for the developers: what changes and why"},
                      "files": {"type": "array", "items": {"type": "object", "properties": {
                          "path": {"type": "string"},
                          "content": {"type": "string", "description": "the whole new content of the file"}},
                        "required": ["path", "content"], "additionalProperties": false}}},
                     "required": ["name", "description", "files"]}""")),
            new LlmClient.Tool("no_fix", "Gives up: no safe fix for this finding, with the reason.", schema("""
                    {"type": "object", "properties": {"reason": {"type": "string"}}, "required": ["reason"]}""")));

    private final LlmClient llm;
    private final List<String> requests;
    private final int maxTurns;
    private final Consumer<String> log;
    private LlmClient.Usage usage = LlmClient.Usage.NONE;

    /**
     * @param requests the requests the prover replays (shown to the model: their JSON must not change)
     * @param maxTurns model turns per fix (each {@code read_file} round is one)
     */
    public LlmFixGenerator(LlmClient llm, List<String> requests, int maxTurns, Consumer<String> log) {
        this.llm = llm;
        this.requests = List.copyOf(requests);
        this.maxTurns = maxTurns;
        this.log = log;
    }

    @Override
    public String name() {
        return "llm";
    }

    public String model() {
        return llm.model();
    }

    /** Tokens used so far by every fix of this generator. */
    public synchronized LlmClient.Usage usage() {
        return usage;
    }

    @Override
    public boolean handles(JsonNode bundle) {
        return KINDS.contains(bundle.path("kind").asText());
    }

    @Override
    public Result generate(JsonNode bundle, Path app) throws Exception {
        return retry(List.of(bundle), app, List.of());
    }

    @Override
    public Result retry(List<JsonNode> bundles, Path app, List<Attempt> rejected) throws Exception {
        Path root = app.toAbsolutePath().normalize();
        String id = bundles.get(0).path("id").asText();
        LlmClient.Session session = llm.open(SYSTEM, TOOLS);
        List<LlmClient.Input> next = List.of(new LlmClient.Text(prompt(bundles, root, rejected)));
        boolean nudged = false;
        for (int turn = 1; turn <= maxTurns; turn++) {
            LlmClient.Reply reply = session.send(next);
            add(reply.usage());
            switch (reply.stop()) {
                case REFUSAL -> {
                    return Result.skip("the model declined the request");
                }
                case MAX_TOKENS -> {
                    return Result.skip("the model's answer was cut by the token limit");
                }
                case END, OTHER -> {
                    if (reply.calls().isEmpty()) {
                        if (nudged) return Result.skip("the model answered without proposing a fix: " + abbreviate(reply.text()));
                        nudged = true;
                        next = List.of(new LlmClient.Text("Call propose_fix with the fix, or no_fix with the reason."));
                        continue;
                    }
                }
                case TOOL_USE -> { }
            }
            List<LlmClient.Input> results = new ArrayList<>();
            for (LlmClient.ToolCall call : reply.calls()) {
                switch (call.name()) {
                    case "read_file" -> results.add(read(root, call));
                    case "no_fix" -> {
                        return Result.skip("no fix: " + call.input().path("reason").asText());
                    }
                    case "propose_fix" -> {
                        Object outcome = fix(root, call.input(), bundles);
                        if (outcome instanceof Fix fix) {
                            log.accept(String.format("%-12s %-32s %s (%d turn(s))", "llm", id, fix.name(), turn));
                            return Result.of(fix);
                        }
                        results.add(new LlmClient.ToolResult(call.id(), (String) outcome, true));
                    }
                    default -> results.add(new LlmClient.ToolResult(call.id(), "unknown tool " + call.name(), true));
                }
            }
            next = results;
        }
        return Result.skip("no fix proposed within " + maxTurns + " turns");
    }

    private synchronized void add(LlmClient.Usage u) {
        usage = usage.plus(u);
    }

    // ------------------------------------------------------------------ prompt

    String prompt(List<JsonNode> bundles, Path root, List<Attempt> rejected) throws IOException {
        StringBuilder p = new StringBuilder();
        p.append(bundles.size() == 1 ? "# The finding to fix\n\n" : "# The findings to fix (one cause)\n\n");
        Set<String> given = new LinkedHashSet<>();
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        for (JsonNode b : bundles) {
            ObjectNode f = b.deepCopy();
            f.remove("sources");
            p.append("```json\n").append(JSON.writeValueAsString(f)).append("\n```\n\n");
            for (JsonNode s : b.path("sources")) sources.putIfAbsent(s.path("path").asText(), s);
        }
        p.append("# Source files named by the evidence\n\n");
        for (JsonNode s : sources.values()) {
            String path = s.path("path").asText();
            Path file = resolve(root, path);
            if (file == null || !Files.isRegularFile(file) || !visible(path)) continue;
            given.add(path);
            p.append("## ").append(path).append(" (").append(roles(s));
            if (s.has("line")) p.append(", line ").append(s.path("line").asInt());
            p.append(")\n\n").append(fenced(path, read(file))).append('\n');
        }
        p.append("# Other files of the application (read_file)\n\n");
        files(root).stream().filter(f -> !given.contains(f)).forEach(f -> p.append("- ").append(f).append('\n'));
        p.append("\n# Requests replayed by the proof (JSON compared unless marked status)\n\n");
        requests.forEach(r -> p.append("- ").append(r).append('\n'));
        if (!rejected.isEmpty()) {
            p.append("\n# Rejected fixes\n\nThese fixes were proven and rejected. Propose a different fix that "
                    + "addresses every reason; the source files above are the current ones, without these patches.\n");
            int n = 1;
            for (Attempt a : rejected) {
                p.append("\n## Attempt ").append(n++).append(": ").append(a.fix().description()).append("\n\n")
                        .append("```diff\n").append(a.patch()).append("```\n\nReasons:\n");
                a.reasons().forEach(r -> p.append("- ").append(r).append('\n'));
            }
        }
        p.append("\nPropose the fix with propose_fix.\n");
        return p.toString();
    }

    private static String roles(JsonNode source) {
        List<String> r = new ArrayList<>();
        source.path("roles").forEach(x -> r.add(x.asText()));
        return String.join(", ", r);
    }

    public static String fenced(String path, String content) {
        String lang = path.endsWith(".java") ? "java" : path.endsWith(".yml") || path.endsWith(".yaml") ? "yaml"
                : path.endsWith(".xml") ? "xml" : path.endsWith(".sql") ? "sql" : "";
        String fence = content.contains("```") ? "~~~~" : "```";
        return fence + lang + "\n" + content + (content.endsWith("\n") ? "" : "\n") + fence + "\n";
    }

    /**
     * The application's sources ({@code src/}) and build files, relative paths; build output, VCS and IDE folders
     * skipped. Nothing else is shown or readable: documentation may describe the expected fixes (the demo's
     * {@code TRAPS.md} does), and the measure is what the model finds from the evidence and the code.
     */
    public static List<String> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> !skipped(root, f))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .filter(LlmFixGenerator::visible)
                    .sorted()
                    .limit(MAX_LISTED_FILES)
                    .toList();
        }
    }

    static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "gradle.properties");

    /** A relative path the model may see and read. */
    public static boolean visible(String relative) {
        return relative.startsWith("src/") || BUILD_FILES.contains(relative);
    }

    public static boolean skipped(Path root, Path f) {
        for (Path part : root.relativize(f)) {
            if (SourceLocator.SKIPPED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ tools

    private LlmClient.ToolResult read(Path root, LlmClient.ToolCall call) {
        String path = call.input().path("path").asText();
        Path file = resolve(root, path);
        if (file == null) return new LlmClient.ToolResult(call.id(), "not a path inside the application: " + path, true);
        String rel = root.relativize(file).toString().replace('\\', '/');
        if (!Files.isRegularFile(file)) return new LlmClient.ToolResult(call.id(), "no such file: " + path, true);
        if (!visible(rel) || skipped(root, file)) {
            return new LlmClient.ToolResult(call.id(), "not readable: only the files listed can be read", true);
        }
        try {
            String text = read(file);
            if (text.length() > MAX_FILE_CHARS) {
                text = text.substring(0, MAX_FILE_CHARS) + "\n[truncated at " + MAX_FILE_CHARS + " characters]";
            }
            return new LlmClient.ToolResult(call.id(), text, false);
        } catch (IOException e) {
            return new LlmClient.ToolResult(call.id(), "cannot read " + path + ": " + e.getMessage(), true);
        }
    }

    /** A {@link Fix}, or the error message sent back to the model. */
    Object fix(Path root, JsonNode input, List<JsonNode> bundles) {
        String name = slug(input.path("name").asText(""));
        String description = input.path("description").asText("").strip().replaceAll("\\s+", " ");
        if (!input.path("files").isArray() || input.path("files").isEmpty()) return "files: at least one file";
        Map<String, Fix.Change> files = new LinkedHashMap<>();
        for (JsonNode f : input.path("files")) {
            String path = f.path("path").asText("").replace('\\', '/');
            Path file = resolve(root, path);
            if (file == null) return "not a path inside the application: " + path;
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (skipped(root, file) || !visible(rel)) return "not a source file: " + path;
            if (!f.path("content").isTextual() || f.path("content").asText().isBlank()) {
                return "empty content for " + path + " (deleting files is not supported)";
            }
            try {
                String before = Files.isRegularFile(file) ? read(file) : null;
                String after = f.path("content").asText();
                if (before != null && !after.endsWith("\n") && before.endsWith("\n")) after += "\n";
                if (after.equals(before)) continue;
                if (files.put(rel, new Fix.Change(before, after)) != null) return "file listed twice: " + path;
            } catch (IOException e) {
                return "cannot read " + path + ": " + e.getMessage();
            }
        }
        if (files.isEmpty()) return "the fix changes nothing: every file is identical to the current one";
        if (name.isEmpty()) name = bundles.get(0).path("kind").asText().toLowerCase(Locale.ROOT).replace('_', '-');
        if (description.isEmpty()) description = bundles.get(0).path("title").asText();
        return new Fix(name(), "llm-" + name, description, files);
    }

    /** {@code path} inside {@code root}, or null (absolute, {@code ..}, outside). */
    public static Path resolve(Path root, String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\0")) return null;
        try {
            Path p = root.resolve(path).normalize();
            return p.startsWith(root) && !p.equals(root) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static String slug(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (out.startsWith("llm-")) out = out.substring(4);
        return out.length() > 60 ? out.substring(0, 60).replaceAll("-+$", "") : out;
    }

    private static String abbreviate(String s) {
        String one = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

    private static JsonNode schema(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
