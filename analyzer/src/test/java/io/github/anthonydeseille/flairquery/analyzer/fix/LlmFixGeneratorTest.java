package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient.Stop;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.noFix;
import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.propose;
import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.read;
import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.resultOf;
import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.text;
import static io.github.anthonydeseille.flairquery.analyzer.fix.ScriptedLlm.textOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM generator and the fix command's orchestration (templates first, LLM fallback, retries with the
 * prover's reasons) with a scripted model and a stand-in proof: no network, no Docker.
 */
class LlmFixGeneratorTest {

    static final Path DEMO = GeneratorsTest.DEMO;
    static final String PKG = "src/main/java/io/github/anthonydeseille/flairquery/demo/";
    static final String BOOK_REPOSITORY = PKG + "repository/BookRepository.java";
    static final String BOOK_SERVICE = PKG + "service/BookService.java";
    static final String BOOK_CONTROLLER = PKG + "web/BookController.java";
    static final List<String> REQUESTS = List.of("GET /books/titles?authorId=7", "GET /reviews/ranking?page=400  (status only)");

    @TempDir
    Path tmp;

    @BeforeAll
    static void bundles() throws Exception {
        GeneratorsTest.bundles();
    }

    static JsonNode bundle(String kind, String endpoint, String subjectSuffix) {
        return GeneratorsTest.bundles.stream()
                .filter(b -> b.path("kind").asText().equals(kind) && b.path("endpoint").asText().equals(endpoint))
                .filter(b -> subjectSuffix == null || b.path("evidence").path("entity").asText("").endsWith(subjectSuffix))
                .findFirst().orElseThrow();
    }

    static JsonNode titles() {
        return bundle("OVER_FETCHING", "GET /books/titles", "Book");
    }

    static LlmFixGenerator generator(ScriptedLlm llm) {
        return new LlmFixGenerator(llm, REQUESTS, 6, s -> { });
    }

    static ScriptedLlm script(List<Function<List<LlmClient.Input>, LlmClient.Reply>> turns) {
        return new ScriptedLlm(n -> turns);
    }

    static String demo(String path) throws Exception {
        return Files.readString(DEMO.resolve(path));
    }

    // ------------------------------------------------------------------ generator

    @Test
    void thePromptCarriesTheEvidenceTheSourcesTheFileListAndTheRequests() throws Exception {
        String repository = demo(BOOK_REPOSITORY);
        ScriptedLlm llm = script(List.of(
                in -> read(BOOK_CONTROLLER),
                in -> propose("Book titles projection", "Select id and title only.",
                        BOOK_REPOSITORY, repository.replace("}\n", "    // projection\n}\n"))));
        FixGenerator.Result r = generator(llm).generate(titles(), DEMO);

        String prompt = textOf(llm.sessions.get(0).get(0));
        assertThat(prompt).contains(titles().path("id").asText()).contains("\"kind\" : \"OVER_FETCHING\"")
                .contains("largeColumnsNeverRead")
                // the sources of the bundle, read from disk, not repeated in the finding's JSON
                .contains("## " + BOOK_SERVICE + " (caller, line ").contains("public List<BookTitleDto> titlesOfAuthor")
                .doesNotContain("\"sources\"")
                // the other files, to read on demand
                .contains("- " + BOOK_CONTROLLER).contains("- pom.xml").doesNotContain("- target/")
                // documentation is neither listed nor readable: TRAPS.md describes the expected fixes
                .doesNotContain("TRAPS.md")
                .contains("- GET /books/titles?authorId=7")
                .doesNotContain("# Rejected fixes");
        // read_file answered with the file
        LlmClient.ToolResult read = resultOf(llm.sessions.get(0).get(1));
        assertThat(read.error()).isFalse();
        assertThat(read.content()).isEqualTo(demo(BOOK_CONTROLLER));

        Fix fix = r.fix();
        assertThat(fix.generator()).isEqualTo("llm");
        assertThat(fix.name()).isEqualTo("llm-book-titles-projection");
        assertThat(fix.description()).isEqualTo("Select id and title only.");
        assertThat(fix.files()).containsOnlyKeys(BOOK_REPOSITORY);
        assertThat(fix.files().get(BOOK_REPOSITORY).before()).isEqualTo(repository);
        assertThat(applies(fix.patch("demo-app"))).isTrue();
    }

    @Test
    void newFilesAreAddedAndAMissingFinalNewlineIsKept() throws Exception {
        String service = demo(BOOK_SERVICE);
        ScriptedLlm llm = script(List.of(in -> propose("titles", "Projection.",
                PKG + "repository/BookTitleView.java", "package x;\n\npublic interface BookTitleView { }\n",
                BOOK_SERVICE, service.replace("books.findByAuthorIdOrderById", "books.findTitlesByAuthorId").stripTrailing())));
        Fix fix = generator(llm).generate(titles(), DEMO).fix();

        assertThat(fix.files()).containsOnlyKeys(PKG + "repository/BookTitleView.java", BOOK_SERVICE);
        assertThat(fix.files().get(PKG + "repository/BookTitleView.java").before()).isNull();
        assertThat(fix.files().get(BOOK_SERVICE).after()).endsWith("}\n");
        String patch = fix.patch("demo-app");
        assertThat(patch).contains("new file mode 100644\n--- /dev/null\n+++ b/demo-app/" + PKG + "repository/BookTitleView.java");
        assertThat(applies(patch)).isTrue();
    }

    @Test
    void pathsOutsideTheApplicationAreRefusedAndTheModelIsTold() throws Exception {
        String service = demo(BOOK_SERVICE);
        ScriptedLlm llm = script(List.of(
                in -> read("../pom.xml"),
                in -> read("/etc/hosts"),
                in -> read("TRAPS.md"),
                in -> propose("escape", "Outside.", "../agent/pom.xml", "<project/>\n"),
                in -> propose("build", "Build output.", "target/classes/x.java", "class X { }\n"),
                in -> propose("same", "No change.", BOOK_SERVICE, service),
                in -> propose("ok", "Inside.", BOOK_SERVICE, service.replace("@Service", "@Service // x"))));
        Fix fix = new LlmFixGenerator(llm, REQUESTS, 10, s -> { }).generate(titles(), DEMO).fix();

        List<List<LlmClient.Input>> turns = llm.sessions.get(0);
        assertThat(resultOf(turns.get(1))).satisfies(t -> {
            assertThat(t.error()).isTrue();
            assertThat(t.content()).contains("not a path inside the application");
        });
        assertThat(resultOf(turns.get(2)).content()).contains("not a path inside the application: /etc/hosts");
        assertThat(resultOf(turns.get(3)).content()).contains("not readable");
        assertThat(resultOf(turns.get(4)).content()).contains("not a path inside the application: ../agent/pom.xml");
        assertThat(resultOf(turns.get(5)).content()).contains("not a source file: target/classes/x.java");
        assertThat(resultOf(turns.get(6)).content()).contains("the fix changes nothing");
        assertThat(fix.name()).isEqualTo("llm-ok");
        assertThat(fix.files()).containsOnlyKeys(BOOK_SERVICE);
    }

    @Test
    void noFixRefusalSilenceAndEndlessReadingProduceNoFix() throws Exception {
        assertThat(generator(script(List.of(in -> noFix("needs an API change")))).generate(titles(), DEMO).skipped())
                .isEqualTo("no fix: needs an API change");
        assertThat(generator(script(List.of(in -> text(Stop.REFUSAL, "")))).generate(titles(), DEMO).skipped())
                .contains("declined");
        assertThat(generator(script(List.of(in -> text(Stop.MAX_TOKENS, "")))).generate(titles(), DEMO).skipped())
                .contains("token limit");

        ScriptedLlm silent = script(List.of(in -> text(Stop.END, "I would use a projection."),
                in -> text(Stop.END, "Really, a projection.")));
        assertThat(generator(silent).generate(titles(), DEMO).skipped())
                .startsWith("the model answered without proposing a fix: Really");
        assertThat(textOf(silent.sessions.get(0).get(1))).contains("Call propose_fix");

        List<Function<List<LlmClient.Input>, LlmClient.Reply>> reads = new ArrayList<>();
        for (int i = 0; i < 6; i++) reads.add(in -> read(BOOK_CONTROLLER));
        assertThat(generator(script(reads)).generate(titles(), DEMO).skipped()).isEqualTo("no fix proposed within 6 turns");
    }

    @Test
    void aRetryShowsTheRejectedPatchesAndTheReasons() throws Exception {
        String service = demo(BOOK_SERVICE);
        ScriptedLlm llm = script(List.of(in -> propose("second", "Second try.", BOOK_SERVICE, service + "// 2\n")));
        Fix first = new Fix("llm", "llm-first", "First try.", java.util.Map.of(BOOK_SERVICE,
                new Fix.Change(service, service + "// 1\n")));
        FixGenerator.Result r = generator(llm).retry(List.of(titles()), DEMO, List.of(new FixGenerator.Attempt(
                first, first.patch("demo-app"), List.of("response changed: /books/titles?authorId=7: JSON differs at /0/title"))));

        String prompt = textOf(llm.sessions.get(0).get(0));
        assertThat(prompt).contains("# Rejected fixes").contains("## Attempt 1: First try.")
                .contains("+// 1").contains("- response changed: /books/titles?authorId=7: JSON differs at /0/title");
        assertThat(r.fix().name()).isEqualTo("llm-second");
    }

    @Test
    void handlesTrapsOneThreeFiveSixAndTheTemplateKindsButNotTheSortSpill() {
        LlmFixGenerator g = generator(script(List.of()));
        assertThat(GeneratorsTest.bundles).filteredOn(g::handles).extracting(b -> b.path("kind").asText()).doesNotContain("SORT_SPILL")
                .contains("N_PLUS_ONE_LAZY", "OVER_FETCHING", "IN_MEMORY_PAGINATION", "OPEN_IN_VIEW", "USELESS_EAGER", "MISSING_INDEX");
    }

    // ------------------------------------------------------------------ fix command

    @Test
    void templatesComeFirstAndTheLlmTakesTheRest() throws Exception {
        String service = demo(BOOK_SERVICE);
        AtomicInteger n = new AtomicInteger();
        ScriptedLlm llm = new ScriptedLlm(i -> List.of(in -> propose("fix-" + n.incrementAndGet(), "LLM fix.",
                BOOK_SERVICE, service + "// " + textOf(in).hashCode() + "\n")));
        List<String> log = new ArrayList<>();
        List<FixCommand.Proposal> proposals = FixCommand.generate(new ArrayList<>(GeneratorsTest.bundles), DEMO, "demo-app",
                FixCommand.generators(), generator(llm), log::add);

        // the two template fixes as in step 3, an LLM fix for every other finding but the sort spills
        assertThat(proposals).extracting(p -> p.fix().generator()).filteredOn(g -> !g.equals("llm"))
                .containsExactly("lazy-association", "index-migration");
        long llmFindings = GeneratorsTest.bundles.stream().map(b -> b.path("kind").asText())
                .filter(k -> !k.equals("USELESS_EAGER") && !k.equals("MISSING_INDEX") && !k.equals("SORT_SPILL")).count();
        assertThat(llm.sessions).hasSize((int) llmFindings);
        assertThat(proposals).filteredOn(p -> p.fix().generator().equals("llm")).hasSize((int) llmFindings);
        assertThat(log).filteredOn(l -> l.startsWith("no-generator")).allMatch(l -> l.endsWith("SORT_SPILL"));
    }

    @Test
    void aSkippedTemplateFallsBackToTheLlm() throws Exception {
        ObjectNode b = ScriptedLlm.JSON.createObjectNode().put("id", "missing-index-flyway").put("kind", "MISSING_INDEX");
        b.putObject("evidence").put("table", "review").put("column", "reviewer_name");
        b.putArray("sources").addObject().put("path", "src/main/resources/db/migration/V1__init.sql").putArray("roles").add("migration");
        ScriptedLlm llm = script(List.of(in -> propose("flyway-index", "Flyway migration.",
                "src/main/resources/db/migration/V2__index.sql", "create index idx on review (reviewer_name);\n")));
        List<String> log = new ArrayList<>();
        List<FixCommand.Proposal> proposals = FixCommand.generate(List.of(b), DEMO, "demo-app", FixCommand.generators(),
                generator(llm), log::add);

        assertThat(proposals).singleElement().satisfies(p -> assertThat(p.fix().name()).isEqualTo("llm-flyway-index"));
        assertThat(log).anyMatch(l -> l.startsWith("skipped") && l.contains("Flyway is not handled yet") && l.endsWith("-> llm"));
    }

    @Test
    void aRejectedFixIsRetriedWithTheReasonsUntilProvenOrOutOfRetries() throws Exception {
        String service = demo(BOOK_SERVICE);
        AtomicInteger n = new AtomicInteger();
        ScriptedLlm llm = new ScriptedLlm(i -> List.of(in -> {
            int k = n.incrementAndGet();
            return propose("titles-" + k, "Try " + k + ".", BOOK_SERVICE, service + "// " + k + "\n");
        }));
        LlmFixGenerator g = generator(llm);
        List<FixCommand.Proposal> first = FixCommand.generate(List.of(titles()), DEMO, "demo-app",
                FixCommand.generators(), g, s -> { });
        List<String> proven = new ArrayList<>();
        // attempt 1 breaks the JSON, attempt 2 is accepted
        FixCommand.Proof proof = (proposals, patches) -> proposals.stream().map(p -> {
            proven.add(p.fix().name());
            return p.fix().name().equals("llm-titles-1")
                    ? verdict(Verdict.Status.REJECTED, "response changed: /books/titles?authorId=7: JSON differs at /3")
                    : verdict(Verdict.Status.ACCEPTED);
        }).toList();
        List<FixCommand.Outcome> outcomes = FixCommand.proveAndRetry(first, DEMO, "demo-app", g, 2, proof,
                tmp.resolve("fixes"), s -> { });

        assertThat(proven).containsExactly("llm-titles-1", "llm-titles-2");
        assertThat(outcomes).hasSize(2);
        FixCommand.Proposal second = outcomes.get(1).proposal();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.replaces()).isEqualTo("llm-titles-1");
        assertThat(second.findings()).containsExactly(titles().path("id").asText());
        assertThat(second.rejected()).singleElement().satisfies(a -> assertThat(a.reasons()).containsExactly(
                "response changed: /books/titles?authorId=7: JSON differs at /3"));
        assertThat(textOf(llm.sessions.get(1).get(0))).contains("JSON differs at /3").contains("+// 1");
        assertThat(FixCommand.finals(outcomes)).singleElement().satisfies(o -> assertThat(o.verdict().accepted()).isTrue());

        FixCommand.writeIndex(outcomes, tmp.resolve("fixes"));
        JsonNode index = ScriptedLlm.JSON.readTree(tmp.resolve("fixes/index.json").toFile());
        assertThat(index.get(1).path("replaces").asText()).isEqualTo("llm-titles-1");
        assertThat(index.get(1).path("proposed").asBoolean()).isTrue();
        assertThat(index.get(0).path("proposed").asBoolean()).isFalse();
    }

    @Test
    void retriesAreBoundedAndOnlyForRejections() throws Exception {
        String service = demo(BOOK_SERVICE);
        AtomicInteger n = new AtomicInteger();
        ScriptedLlm llm = new ScriptedLlm(i -> List.of(in -> {
            int k = n.incrementAndGet();
            return propose("t-" + k, "Try " + k + ".", BOOK_SERVICE, service + "// " + k + "\n");
        }));
        LlmFixGenerator g = generator(llm);
        List<FixCommand.Proposal> first = FixCommand.generate(List.of(titles()), DEMO, "demo-app", List.of(), g, s -> { });
        List<FixCommand.Outcome> rejected = FixCommand.proveAndRetry(first, DEMO, "demo-app", g, 2,
                (ps, patches) -> ps.stream().map(p -> verdict(Verdict.Status.REJECTED, "no measurable gain")).toList(),
                tmp.resolve("a"), s -> { });
        assertThat(rejected).extracting(FixCommand.Outcome::name).containsExactly("llm-t-1", "llm-t-2", "llm-t-3");
        assertThat(rejected.get(2).proposal().rejected()).hasSize(2);

        int sessions = llm.sessions.size();
        List<FixCommand.Outcome> inconclusive = FixCommand.proveAndRetry(
                FixCommand.generate(List.of(titles()), DEMO, "demo-app", List.of(), g, s -> { }), DEMO, "demo-app", g, 2,
                (ps, patches) -> ps.stream().map(p -> verdict(Verdict.Status.INCONCLUSIVE, "not reproduced")).toList(),
                tmp.resolve("b"), s -> { });
        assertThat(inconclusive).hasSize(1);
        assertThat(llm.sessions).hasSize(sessions + 1);
    }

    @Test
    void theSamePatchAgainIsNotProvenTwice() throws Exception {
        String service = demo(BOOK_SERVICE);
        ScriptedLlm llm = new ScriptedLlm(i -> List.of(in -> propose("same", "Same.", BOOK_SERVICE, service + "// same\n")));
        LlmFixGenerator g = generator(llm);
        List<String> log = new ArrayList<>();
        List<FixCommand.Outcome> outcomes = FixCommand.proveAndRetry(
                FixCommand.generate(List.of(titles()), DEMO, "demo-app", List.of(), g, s -> { }), DEMO, "demo-app", g, 2,
                (ps, patches) -> ps.stream().map(p -> verdict(Verdict.Status.REJECTED, "regression")).toList(),
                tmp.resolve("c"), log::add);
        assertThat(outcomes).hasSize(1);
        assertThat(log).anyMatch(l -> l.startsWith("no-retry") && l.endsWith("the same patch again"));
    }

    static Verdict verdict(Verdict.Status status, String... reasons) {
        return new Verdict(status, List.of(reasons), ScriptedLlm.JSON.createObjectNode().put("status", status.name()));
    }

    /** {@code git apply --check} on a copy of the demo app's sources at {@code <tmp>/demo-app}. */
    private boolean applies(String patch) throws Exception {
        Path root = Files.createTempDirectory(tmp, "repo");
        Path app = root.resolve("demo-app");
        try (Stream<Path> files = Files.walk(DEMO.resolve("src/main"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path to = app.resolve(DEMO.relativize(f).toString());
                Files.createDirectories(to.getParent());
                Files.copy(f, to);
            }
        }
        Path p = root.resolve("fix.patch");
        Files.writeString(p, patch);
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--check", p.toString()).directory(root.toFile()).inheritIO();
        pb.environment().put("GIT_CEILING_DIRECTORIES", root.getParent().toString());
        return pb.start().waitFor() == 0;
    }
}
