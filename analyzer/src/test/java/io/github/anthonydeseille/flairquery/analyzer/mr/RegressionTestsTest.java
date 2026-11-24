package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionTestsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DEMO = Path.of(System.getProperty("fq.demo.sources", "../demo-app")).toAbsolutePath().normalize();
    static final String PKG = "io.github.anthonydeseille.flairquery.demo.web";
    static final String PATH = "src/test/java/io/github/anthonydeseille/flairquery/demo/web/AuthorsQueriesRegressionTest.java";
    static final String CONTENT = "package " + PKG + ";\n\nclass AuthorsQueriesRegressionTest {\n}\n";

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ Surefire reports and verdicts

    Path report(String body) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("surefire-reports"));
        Files.writeString(dir.resolve("TEST-a.BTest.xml"), body);
        return dir;
    }

    @Test
    void aSurefireReportGivesCountsAndTheFirstMessages() throws Exception {
        Path dir = report("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="a.BTest" tests="2" errors="0" skipped="0" failures="1">
                  <testcase name="x"><failure message="GET /authors ran 22 SQL statements,
                     at most 2 expected" type="org.opentest4j.AssertionFailedError">stack</failure></testcase>
                  <testcase name="y"/>
                </testsuite>""");
        TestProof.Run r = TestProof.report(dir, "a.BTest");
        assertThat(r).isEqualTo(new TestProof.Run(2, 1, 0, 0, "GET /authors ran 22 SQL statements, at most 2 expected", null));
        assertThat(TestProof.report(dir, "a.Missing")).isEqualTo(TestProof.Run.NOT_RUN);
    }

    @Test
    void redMeansAnAssertionFailureOnTheCurrentCodeAndGreenWithTheFix() {
        TestProof.Run red = new TestProof.Run(1, 1, 0, 0, "ran 22 statements", null);
        TestProof.Run green = new TestProof.Run(1, 0, 0, 0, null, null);
        TestProof.Run error = new TestProof.Run(1, 0, 1, 0, null, "ApplicationContext failure");
        TestProof.Run skipped = new TestProof.Run(1, 0, 0, 1, null, null);

        assertThat(TestProof.judge(red, "", green, "")).isEqualTo(new TestProof.Verdict("ran 22 statements", List.of()));
        assertThat(TestProof.judge(green, "", green, "").reasons())
                .containsExactly("it passes on the current code: its assertions do not catch the problem");
        assertThat(TestProof.judge(error, "", green, "").reasons())
                .containsExactly("on the current code it fails with an error, not an assertion: ApplicationContext failure");
        assertThat(TestProof.judge(TestProof.Run.NOT_RUN, "[ERROR] cannot find symbol AuthorSummary", green, "").reasons())
                .containsExactly("on the current code it did not run (it must compile against the current code, not only "
                        + "the fixed one): [ERROR] cannot find symbol AuthorSummary");
        assertThat(TestProof.judge(skipped, "", green, "").reasons()).containsExactly("on the current code it was skipped");
        assertThat(TestProof.judge(red, "", red, "").reasons()).containsExactly("it fails with the fix: ran 22 statements");
    }

    // ------------------------------------------------------------------ what the model may propose

    static JsonNode proposal(String path, String content) {
        return JSON.createObjectNode().put("path", path).put("content", content).put("description", " 3 authors,\n 4 books ");
    }

    @Test
    void aTestIsOneNewJavaClassUnderSrcTestJavaMatchingItsPackage() {
        Object ok = LlmTestGenerator.test(DEMO, proposal(PATH, CONTENT));
        assertThat(ok).isEqualTo(new RegressionTest(PATH, PKG + ".AuthorsQueriesRegressionTest", CONTENT, "3 authors, 4 books"));

        assertThat(LlmTestGenerator.test(DEMO, proposal("src/main/java/X.java", CONTENT)))
                .isEqualTo("the test must be a .java file under src/test/java: src/main/java/X.java");
        assertThat(LlmTestGenerator.test(DEMO, proposal("../pom.xml", CONTENT))).asString().startsWith("not a path inside");
        assertThat(LlmTestGenerator.test(DEMO, proposal(
                "src/test/java/io/github/anthonydeseille/flairquery/demo/SchemaMappingTest.java", CONTENT)))
                .asString().startsWith("the file exists already");
        assertThat(LlmTestGenerator.test(DEMO, proposal("src/test/java/x/AuthorsQueriesRegressionTest.java", CONTENT)))
                .isEqualTo("the path does not match the package and class: expected " + PATH);
        assertThat(LlmTestGenerator.test(DEMO, proposal(PATH.replace("RegressionTest", "Check"),
                CONTENT.replace("RegressionTest", "Check")))).asString().startsWith("the class name must end with Test");
    }

    // ------------------------------------------------------------------ generation and retries

    static FixEntry fix(Path patch) {
        return new FixEntry("llm-trap1", "llm", "authors from two projections", List.of("n-plus-one-lazy-1"), 1, patch,
                "ACCEPTED", true, List.of("n-plus-one-lazy-1"), null);
    }

    static ObjectNode report() {
        ObjectNode r = JSON.createObjectNode().put("kind", "N_PLUS_ONE_LAZY").put("endpoint", "GET /authors")
                .put("subject", "Author.books");
        r.putArray("findingsResolved").addObject().put("kind", "N_PLUS_ONE_LAZY").put("endpoint", "GET /authors")
                .put("title", "N+1: Author.books is loaded lazily 20 times per request");
        var eps = r.putArray("endpoints");
        ObjectNode t = eps.addObject().put("endpoint", "GET /authors").put("target", true).put("sameSql", false);
        t.putObject("before").put("statementsPerRequest", 22.0).put("sqlMsPerRequest", 57.3);
        t.putObject("after").put("statementsPerRequest", 2.0).put("sqlMsPerRequest", 8.0);
        ObjectNode o = eps.addObject().put("endpoint", "GET /books").put("target", false).put("sameSql", true);
        o.putObject("before").put("statementsPerRequest", 45.0).put("sqlMsPerRequest", 1);
        o.putObject("after").put("statementsPerRequest", 45.0).put("sqlMsPerRequest", 1);
        return r;
    }

    /** A model replying with the given replies, recording the text inputs it got. */
    static LlmClient model(List<String> prompts, LlmClient.Reply... replies) {
        Iterator<LlmClient.Reply> it = List.of(replies).iterator();
        return new LlmClient() {
            @Override
            public Session open(String system, List<Tool> tools) {
                return inputs -> {
                    inputs.forEach(i -> prompts.add(i instanceof Text t ? t.text() : ((ToolResult) i).content()));
                    return it.next();
                };
            }

            @Override
            public String model() {
                return "fake";
            }
        };
    }

    static LlmClient.Reply call(String tool, JsonNode input) {
        return new LlmClient.Reply(LlmClient.Stop.TOOL_USE, "", List.of(new LlmClient.ToolCall("c" + tool, tool, input)),
                "fake", LlmClient.Usage.NONE);
    }

    @Test
    void thePromptHasTheFixItsProofTheExistingTestsAndTheRefusedAttempts() throws Exception {
        List<String> prompts = new ArrayList<>();
        LlmTestGenerator g = new LlmTestGenerator(model(prompts,
                call("read_file", JSON.createObjectNode().put("path", "../TRAPS.md")),
                call("propose_test", proposal(PATH, CONTENT))), List.of("GET /authors?limit=20"), 5, s -> { });
        RegressionTest refused = new RegressionTest(PATH, PKG + ".Old", "class Old {}\n", "old");
        LlmTestGenerator.Result r = g.generate(fix(tmp.resolve("p.patch")), "--- a/x\n+++ b/x\n", report(), DEMO,
                List.of(new LlmTestGenerator.Attempt(refused, List.of("it passes on the current code"))));

        assertThat(r.test().simpleName()).isEqualTo("AuthorsQueriesRegressionTest");
        String prompt = prompts.get(0);
        assertThat(prompt).contains("authors from two projections", "```diff\n--- a/x\n+++ b/x\n```",
                "Target finding: N_PLUS_ONE_LAZY on GET /authors (Author.books)",
                "- GET /authors (target): 22 -> 2 statements, 57.3 -> 8.0 ms SQL",
                "- GET /authors?limit=20", "## src/test/java/io/github/anthonydeseille/flairquery/demo/SchemaMappingTest.java",
                "class SchemaMappingTest", "- pom.xml", "# Refused tests", "class Old {}", "- it passes on the current code");
        assertThat(prompt).doesNotContain("GET /books:", "TRAPS.md");
        assertThat(prompts.get(1)).isEqualTo("not a path inside the application: ../TRAPS.md");
    }

    @Test
    void aRefusedTestIsGeneratedAgainWithTheReasonsUntilProvenOrOutOfRetries() throws Exception {
        Path patch = Files.writeString(tmp.resolve("fix.patch"), "");
        List<List<String>> seen = new ArrayList<>();
        RegressionTests.Generator gen = (f, p, rep, app, failed) -> {
            seen.add(failed.stream().flatMap(a -> a.reasons().stream()).toList());
            return new LlmTestGenerator.Result(new RegressionTest(PATH, PKG + ".T" + failed.size() + "Test",
                    "class T" + failed.size() + "Test {}\n", "d"), null);
        };
        Iterator<TestProof.Verdict> verdicts = List.of(
                new TestProof.Verdict(null, List.of("it passes on the current code")),
                new TestProof.Verdict("ran 22 statements", List.of())).iterator();
        RegressionTests.Outcome ok = RegressionTests.of(fix(patch), "", report(), DEMO, gen, (t, p) -> verdicts.next(), 2, s -> { });
        assertThat(ok.test().simpleName()).isEqualTo("T1Test");
        assertThat(ok.red()).isEqualTo("ran 22 statements");
        assertThat(ok.attempts()).isEqualTo(2);
        assertThat(seen).containsExactly(List.of(), List.of("it passes on the current code"));

        RegressionTests.Outcome none = RegressionTests.of(fix(patch), "", report(), DEMO, gen,
                (t, p) -> new TestProof.Verdict(null, List.of("it fails with the fix: x")), 1, s -> { });
        assertThat(none.test()).isNull();
        assertThat(none.missing()).isEqualTo("no test proven red / green in 2 attempt(s); last: it fails with the fix: x");

        RegressionTests.Outcome same = RegressionTests.of(fix(patch), "", report(), DEMO,
                (f, p, rep, app, failed) -> new LlmTestGenerator.Result(new RegressionTest(PATH, PKG + ".TTest", "same\n", "d"), null),
                (t, p) -> new TestProof.Verdict(null, List.of("r")), 3, s -> { });
        assertThat(same.missing()).isEqualTo("the same test was proposed again after being refused");
        assertThat(same.attempts()).isEqualTo(2);
    }

    @Test
    void theMergeRequestShowsTheTestRedThenGreenOrWhyThereIsNone() throws Exception {
        Path patch = Files.writeString(tmp.resolve("fix.patch"), "--- a/demo-app/src/main/java/A.java\n+++ b/demo-app/src/main/java/A.java\n");
        ObjectNode r = report().put("finding", "n").put("rounds", 5);
        RegressionTest t = new RegressionTest(PATH, PKG + ".AuthorsQueriesRegressionTest", CONTENT, "3 authors with 4 books");
        String body = Description.of(fix(patch), r, Map.of(), List.of(),
                new RegressionTests.Outcome(t, "GET /authors ran 4 SQL statements | at most 2", 1, null)).body();
        assertThat(body).contains("## Regression test", "`" + PATH + "` (added by this change): 3 authors with 4 books",
                "`mvnw test -Dtest=AuthorsQueriesRegressionTest`",
                "| current (without the fix) | ❌ fails: GET /authors ran 4 SQL statements \\| at most 2 |",
                "| with the fix | ✅ passes |", "  - `demo-app/" + PATH + "`");
        assertThat(Description.of(fix(patch), r, Map.of(), List.of(), RegressionTests.Outcome.none("no test: x", 1)).body())
                .contains("## Regression test\n\nNone: no test: x.\n");
        assertThat(t.patch("demo-app")).startsWith("diff --git a/demo-app/" + PATH + " b/demo-app/" + PATH + "\nnew file mode 100644\n--- /dev/null\n");
    }
}
