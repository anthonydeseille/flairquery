package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.analyzer.fix.LlmFixGenerator;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The regression test of a proven fix, written by a language model: it gets the fix (description and patch), its
 * proof (finding, statements and SQL time per request before / after, plans) and the application's files (its
 * existing tests first), and answers with {@code propose_test} (one new JUnit class) or {@code no_test}. After a
 * failed red / green proof it is asked again with its earlier tests and the reasons.
 *
 * <p>Nothing it writes is trusted: one new file under {@code src/test/java}, a class whose package and name match
 * its path, and only the red / green proof decides.</p>
 */
public final class LlmTestGenerator implements RegressionTests.Generator {

    static final int MAX_INLINE_TESTS = 3;
    static final int MAX_FILE_CHARS = 60_000;
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    static final Pattern CLASS = Pattern.compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+(\\w+)");

    /** A test whose red / green proof failed, with the reasons. */
    public record Attempt(RegressionTest test, List<String> reasons) { }

    /** A test, or why there is none. */
    public record Result(RegressionTest test, String skipped) { }

    static final String SYSTEM = """
            You write the regression test that goes with a performance fix in a Java application (Spring Boot,
            Spring Data JPA, Hibernate 6). FlairQuery observed the problem in the running application, a fix was
            generated and proven on real data: same JSON responses, fewer SQL statements and less SQL time. Your test
            stays in the application so that the problem cannot come back unnoticed.

            Your test is not trusted. It is added to two copies of the application and run with Maven
            (mvnw test -Dtest=<YourClass>): it is accepted only if
            - on the current code (without the fix) it FAILS ON AN ASSERTION: the problem is caught. A compilation
              error, an exception, a context that does not start or a skipped test is refused;
            - with the fix applied it PASSES.

            Write it so:
            - It must compile against the current code AND the fixed code: use only what both have unchanged. Call
              the application through HTTP like a client (the existing tests show how) and create the test data with
              plain SQL (JdbcTemplate) or with entities and repositories the fix does not change. Never use a class,
              method or field the fix adds, renames or changes.
            - Follow the setup of the existing tests (database, Testcontainers, Spring Boot test annotations); add no
              dependency and change no other file.
            - Create a small data set of your own, just big enough to show the problem (for a N+1: several parents
              with several children each). The database starts empty except for what the application's migrations
              create.
            - Assert the cost the fix removes:
              - for a JPA problem (N+1, EAGER never read, over-fetching, JOIN FETCH + pagination, open-in-view): the
                number of SQL statements one request runs, counted with Hibernate statistics
                (spring.jpa.properties.hibernate.generate_statistics=true in the test's properties; the
                EntityManagerFactory unwrapped to org.hibernate.SessionFactory; getStatistics().clear() just before
                the request, getPrepareStatementCount() after it). Assert at most what the fixed code runs for your
                data, strictly less than what the current code runs: work both numbers out from the code and the
                proof (statements per request before and after on the real data);
              - for a missing index: the plan of the statement, with sequential scans disabled (SET enable_seqscan =
                off on the same connection, e.g. in a JdbcTemplate ConnectionCallback): the column must be matched
                by an index condition, not by a Filter.
              Also assert the HTTP status and a basic property of the response (e.g. its number of elements), not
              its whole JSON.
            - Name the class after what it guards, ending with RegressionTest (e.g. AuthorsListQueriesRegressionTest),
              in the package of the code it guards, under src/test/java. Give the assertions a message a developer
              understands when it fails (what was expected, why: the problem and the fix).
            - Read the files you need with read_file before writing; the list of the application's files is given.
            - When ready, call propose_test once with the whole file. If no reliable test can be written, call no_test
              with the reason.""";

    static final List<LlmClient.Tool> TOOLS = List.of(
            new LlmClient.Tool("read_file", "Returns the content of a file of the application (current code, without "
                    + "the fix), by its path relative to the application directory, as listed.", schema("""
                    {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}""")),
            new LlmClient.Tool("propose_test", "Proposes the regression test: one new JUnit 5 class under src/test/java "
                    + "(path relative to the application directory). Call it once, when the test is complete.", schema("""
                    {"type": "object", "properties": {
                      "path": {"type": "string", "description": "e.g. src/test/java/com/acme/web/AuthorsListQueriesRegressionTest.java"},
                      "content": {"type": "string", "description": "the whole content of the file"},
                      "description": {"type": "string", "description": "one sentence for the reviewer: the data it creates and what it asserts"}},
                     "required": ["path", "content", "description"]}""")),
            new LlmClient.Tool("no_test", "Gives up: no reliable regression test for this fix, with the reason.", schema("""
                    {"type": "object", "properties": {"reason": {"type": "string"}}, "required": ["reason"]}""")));

    private final LlmClient llm;
    private final List<String> requests;
    private final int maxTurns;
    private final Consumer<String> log;
    private LlmClient.Usage usage = LlmClient.Usage.NONE;

    /** @param requests the requests the prover replayed, as {@code GET /path?query} */
    public LlmTestGenerator(LlmClient llm, List<String> requests, int maxTurns, Consumer<String> log) {
        this.llm = llm;
        this.requests = List.copyOf(requests);
        this.maxTurns = maxTurns;
        this.log = log;
    }

    public String model() {
        return llm.model();
    }

    public synchronized LlmClient.Usage usage() {
        return usage;
    }

    private synchronized void add(LlmClient.Usage u) {
        usage = usage.plus(u);
    }

    /**
     * @param patch  the fix as a patch (paths prefixed with the application directory)
     * @param report its proof report
     * @param app    the application directory (current code)
     */
    @Override
    public Result generate(FixEntry fix, String patch, JsonNode report, Path app, List<Attempt> failed) throws Exception {
        Path root = app.toAbsolutePath().normalize();
        LlmClient.Session session = llm.open(SYSTEM, TOOLS);
        List<LlmClient.Input> next = List.of(new LlmClient.Text(prompt(fix, patch, report, root, failed)));
        boolean nudged = false;
        for (int turn = 1; turn <= maxTurns; turn++) {
            LlmClient.Reply reply = session.send(next);
            add(reply.usage());
            switch (reply.stop()) {
                case REFUSAL -> {
                    return new Result(null, "the model declined the request");
                }
                case MAX_TOKENS -> {
                    return new Result(null, "the model's answer was cut by the token limit");
                }
                case END, OTHER -> {
                    if (reply.calls().isEmpty()) {
                        if (nudged) return new Result(null, "the model answered without proposing a test");
                        nudged = true;
                        next = List.of(new LlmClient.Text("Call propose_test with the test, or no_test with the reason."));
                        continue;
                    }
                }
                case TOOL_USE -> { }
            }
            List<LlmClient.Input> results = new ArrayList<>();
            for (LlmClient.ToolCall call : reply.calls()) {
                switch (call.name()) {
                    case "read_file" -> results.add(read(root, call));
                    case "no_test" -> {
                        return new Result(null, "no test: " + call.input().path("reason").asText());
                    }
                    case "propose_test" -> {
                        Object outcome = test(root, call.input());
                        if (outcome instanceof RegressionTest t) {
                            log.accept(String.format("%-12s %-44s %s (%d turn(s))", "test", fix.name(), t.simpleName(), turn));
                            return new Result(t, null);
                        }
                        results.add(new LlmClient.ToolResult(call.id(), (String) outcome, true));
                    }
                    default -> results.add(new LlmClient.ToolResult(call.id(), "unknown tool " + call.name(), true));
                }
            }
            next = results;
        }
        return new Result(null, "no test proposed within " + maxTurns + " turns");
    }

    // ------------------------------------------------------------------ prompt

    String prompt(FixEntry fix, String patch, JsonNode report, Path root, List<Attempt> failed) throws IOException {
        StringBuilder p = new StringBuilder("# The fix\n\n").append(fix.description()).append("\n\n```diff\n")
                .append(patch).append(patch.endsWith("\n") ? "" : "\n").append("```\n\n");
        p.append("# Its proof (real data, per request, before -> after)\n\n")
                .append("Target finding: ").append(report.path("kind").asText()).append(" on ")
                .append(report.path("endpoint").asText());
        if (!report.path("subject").asText().isEmpty()) p.append(" (").append(report.path("subject").asText()).append(')');
        p.append("\n\nFindings resolved:\n");
        for (JsonNode r : report.path("findingsResolved")) {
            p.append("- ").append(r.path("kind").asText()).append(" on ").append(r.path("endpoint").asText()).append(": ")
                    .append(r.path("title").asText()).append('\n');
        }
        p.append("\nEndpoints:\n");
        for (JsonNode e : report.path("endpoints")) {
            if (e.path("sameSql").asBoolean() && !e.path("target").asBoolean()) continue;
            p.append(String.format(Locale.ROOT, "- %s%s: %s -> %s statements, %.1f -> %.1f ms SQL%n",
                    e.path("endpoint").asText(), e.path("target").asBoolean() ? " (target)" : "",
                    Description.number(e.path("before").path("statementsPerRequest").asDouble()),
                    Description.number(e.path("after").path("statementsPerRequest").asDouble()),
                    e.path("before").path("sqlMsPerRequest").asDouble(), e.path("after").path("sqlMsPerRequest").asDouble()));
        }
        for (JsonNode pl : report.path("plans")) {
            p.append("\nPlan of `").append(pl.path("sql").asText()).append("`:\n- before: ")
                    .append(pl.path("before").asText()).append("\n- after: ").append(pl.path("after").asText()).append('\n');
        }
        p.append("\n# Requests the proof replayed\n\n");
        requests.forEach(r -> p.append("- ").append(r).append('\n'));

        List<String> files = LlmFixGenerator.files(root);
        List<String> tests = files.stream().filter(f -> f.startsWith("src/test/")).toList();
        p.append("\n# Existing tests\n\n");
        if (tests.isEmpty()) p.append("(none)\n");
        int inline = 0;
        for (String t : tests) {
            if (t.endsWith(".java") && inline < MAX_INLINE_TESTS) {
                inline++;
                p.append("## ").append(t).append("\n\n").append(LlmFixGenerator.fenced(t, Files.readString(root.resolve(t),
                        StandardCharsets.UTF_8))).append('\n');
            } else {
                p.append("- ").append(t).append('\n');
            }
        }
        p.append("\n# Files of the application, current code (read_file)\n\n");
        files.stream().filter(f -> !f.startsWith("src/test/")).forEach(f -> p.append("- ").append(f).append('\n'));
        if (!failed.isEmpty()) {
            p.append("\n# Refused tests\n\nThese tests were run and refused. Write a different test that addresses "
                    + "every reason.\n");
            int n = 1;
            for (Attempt a : failed) {
                p.append("\n## Attempt ").append(n++).append(": ").append(a.test().path()).append("\n\n")
                        .append(LlmFixGenerator.fenced(a.test().path(), a.test().content())).append("\nReasons:\n");
                a.reasons().forEach(r -> p.append("- ").append(r).append('\n'));
            }
        }
        p.append("\nPropose the test with propose_test.\n");
        return p.toString();
    }

    // ------------------------------------------------------------------ tools

    private LlmClient.ToolResult read(Path root, LlmClient.ToolCall call) {
        String path = call.input().path("path").asText();
        Path file = LlmFixGenerator.resolve(root, path);
        if (file == null) return new LlmClient.ToolResult(call.id(), "not a path inside the application: " + path, true);
        String rel = root.relativize(file).toString().replace('\\', '/');
        if (!Files.isRegularFile(file)) return new LlmClient.ToolResult(call.id(), "no such file: " + path, true);
        if (!LlmFixGenerator.visible(rel) || LlmFixGenerator.skipped(root, file)) {
            return new LlmClient.ToolResult(call.id(), "not readable: only the files listed can be read", true);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() > MAX_FILE_CHARS) {
                text = text.substring(0, MAX_FILE_CHARS) + "\n[truncated at " + MAX_FILE_CHARS + " characters]";
            }
            return new LlmClient.ToolResult(call.id(), text, false);
        } catch (IOException e) {
            return new LlmClient.ToolResult(call.id(), "cannot read " + path + ": " + e.getMessage(), true);
        }
    }

    /** A {@link RegressionTest}, or the error message sent back to the model. */
    static Object test(Path root, JsonNode input) {
        String path = input.path("path").asText("").replace('\\', '/');
        String content = input.path("content").asText("");
        String description = input.path("description").asText("").strip().replaceAll("\\s+", " ");
        Path file = LlmFixGenerator.resolve(root, path);
        if (file == null) return "not a path inside the application: " + path;
        String rel = root.relativize(file).toString().replace('\\', '/');
        if (!rel.startsWith("src/test/java/") || !rel.endsWith(".java")) return "the test must be a .java file under src/test/java: " + path;
        if (Files.exists(file)) return "the file exists already: the test must be a new file (" + rel + ")";
        if (content.isBlank()) return "empty content";
        Matcher pkg = PACKAGE.matcher(content);
        Matcher cls = CLASS.matcher(content);
        if (!cls.find()) return "no class declared";
        String simple = cls.group(1);
        String packageName = pkg.find() ? pkg.group(1) : "";
        String expected = "src/test/java/" + (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + simple + ".java";
        if (!rel.equals(expected)) return "the path does not match the package and class: expected " + expected;
        if (!simple.endsWith("Test")) return "the class name must end with Test (Surefire runs *Test classes): " + simple;
        if (!content.endsWith("\n")) content += "\n";
        return new RegressionTest(rel, packageName.isEmpty() ? simple : packageName + "." + simple, content,
                description.isEmpty() ? "regression test of the fix" : description);
    }

    private static JsonNode schema(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
