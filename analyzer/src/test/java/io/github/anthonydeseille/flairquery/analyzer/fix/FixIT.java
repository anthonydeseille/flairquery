package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.mr.Description;
import io.github.anthonydeseille.flairquery.analyzer.mr.FixEntry;
import io.github.anthonydeseille.flairquery.analyzer.mr.FrozenTests;
import io.github.anthonydeseille.flairquery.analyzer.mr.MrCommand;
import io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard;
import io.github.anthonydeseille.flairquery.analyzer.mr.TestProof;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
import io.github.anthonydeseille.flairquery.analyzer.prove.Prover;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 end to end, and its trap scorecard: the deterministic generators turn the bundles (recorded agent +
 * collector responses, real demo sources) into patches (traps 2 and 4), the LLM fixes accepted by
 * {@link LlmFixIT} are replayed as frozen reference patches (traps 1, 3, 5, 6: no API call here), and the prover
 * proves all of them against one baseline on the real demo app (Testcontainers PostgreSQL seeded with corpus S,
 * agent attached). Every trap in scope must be FIXED, the witness CLEAN.
 * <p>
 * Phase 5 on top of it: {@code mr} selects the merge requests of these proven fixes and proves the regression test
 * of each one red on the current code and green with its fix (Maven on copies of the demo, its own Testcontainers
 * setup); the tests are the frozen ones of {@link FrozenTests}. Its scorecard: traps 1, 2, 4 and 6 proposed in 3
 * merge requests (trap 6's own fix redundant with trap 1's), traps 3 and 5 deferred (same lines as trap 1's).
 * <p>
 * When the demo changes and a reference patch or test no longer applies or proves, regenerate them with
 * {@code make llm-it} (copy the accepted patches of {@code analyzer/target/llm-fixes-it} into
 * {@code src/test/resources/fixes}, the tests of {@code LlmTestIT} into {@code src/test/resources/regression-tests}).
 * Needs Docker, python3 and the agent jar.
 */
class FixIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path OUT = REPO.resolve("analyzer/target/fixes-it");
    static final Path REFERENCES = Path.of("src/test/resources/fixes");
    static List<FixCommand.Proposal> generated;
    static List<FixCommand.Proposal> proposals;
    static List<Verdict> verdicts;
    static List<TrapScorecard.Row> scorecard;
    static List<Description.MergeRequest> mrs;
    static JsonNode mrIndex;
    static List<MrScorecard.Row> mrScorecard;

    @BeforeAll
    static void generateAndProve() throws Exception {
        GeneratorsTest.bundles();
        List<JsonNode> bundles = new ArrayList<>(GeneratorsTest.bundles);
        generated = FixCommand.generate(bundles, REPO.resolve("demo-app"), "demo-app", FixCommand.generators(), System.out::println);
        proposals = new ArrayList<>(generated);
        for (int trap : List.of(1, 3, 5, 6)) proposals.add(reference(trap, bundles));
        Prover.Config config = ProveCommand.config(Map.of(
                "repo", REPO.toString(),
                "agent-jar", System.getProperty("fq.agent.jar", REPO.resolve("agent/target/flairquery-agent.jar").toString()),
                "work", REPO.resolve("analyzer/target/prover-fix-it").toString()));
        // templates only: no LLM, no retry
        List<FixCommand.Outcome> outcomes = FixCommand.proveAndRetry(proposals, REPO.resolve("demo-app"), "demo-app",
                null, 0, (p, patches) -> FixCommand.prove(config, p, patches), OUT, System.out::println);
        FixCommand.writeIndex(outcomes, OUT);
        verdicts = outcomes.stream().map(FixCommand.Outcome::verdict).toList();
        scorecard = TrapScorecard.of(outcomes, new java.util.HashSet<>(bundles.stream().map(b -> b.path("id").asText()).toList()));
        System.out.print(TrapScorecard.text(scorecard));
        Files.writeString(OUT.resolve("scorecard.md"), TrapScorecard.markdown(scorecard));

        // phase 5: merge requests of the proven fixes, each with its frozen regression test proven red / green
        List<FixEntry> fixes = FixEntry.read(OUT.resolve("index.json"));
        Map<String, JsonNode> byId = new HashMap<>();
        bundles.forEach(b -> byId.put(b.path("id").asText(), b));
        TestProof proof = new TestProof.Maven(REPO, "demo-app", REPO.resolve("analyzer/target/regression-tests-it"),
                List.of(REPO.resolve("mvnw").toString()));
        mrs = MrCommand.plan(fixes, byId, REPO, "demo-app", OUT.resolve("mr"), FrozenTests.generator(), proof, 0);
        mrIndex = new ObjectMapper().readTree(OUT.resolve("mr/index.json").toFile());
        mrScorecard = MrScorecard.of(fixes, mrIndex, byId);
    }

    /** The frozen LLM fix of a trap ({@code trap<n>-<name>.patch}), for the bundle of the trap's finding. */
    static FixCommand.Proposal reference(int trap, List<JsonNode> bundles) throws Exception {
        Path file;
        try (var files = Files.list(REFERENCES)) {
            file = files.filter(f -> f.getFileName().toString().startsWith("trap" + trap + "-")).findFirst().orElseThrow();
        }
        String finding = TrapScorecard.TRAPS.get(trap - 1).finding();
        JsonNode bundle = bundles.stream().filter(b -> b.path("id").asText().equals(finding)).findFirst().orElseThrow();
        String name = "llm-" + file.getFileName().toString().replaceAll("\\.patch$", "");
        Fix fix = new Fix("llm", name, "reference LLM fix of trap " + trap, Map.of());
        return new FixCommand.Proposal(fix, Files.readString(file), new ArrayList<>(List.of(bundle)));
    }

    static Verdict verdict(String name) {
        for (int i = 0; i < proposals.size(); i++) {
            if (proposals.get(i).fix().name().equals(name)) return verdicts.get(i);
        }
        throw new AssertionError("no proposal " + name);
    }

    static JsonNode endpoint(Verdict v, String name) {
        return StreamSupport.stream(v.report().path("endpoints").spliterator(), false)
                .filter(c -> c.path("endpoint").asText().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void twoProposalsOnePerCause() {
        assertThat(generated).extracting(p -> p.fix().name())
                .containsExactly("lazy-book-publisher", "index-review-reviewer-name");
    }

    @Test
    void trap2LazyPublisherIsProven() {
        Verdict v = verdict("lazy-book-publisher");
        assertThat(v.reasons()).isEmpty();
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        // one change, four findings (one per endpoint that loaded the publishers for nothing)
        assertThat(v.report().path("findings")).hasSize(4);
        assertThat(StreamSupport.stream(v.report().path("findingsResolved").spliterator(), false)
                .map(f -> f.path("id").asText()))
                .contains(BundleBuilder.id("USELESS_EAGER", "GET /books", "Book.publisher"));
        assertThat(v.report().path("schemaChanged").asBoolean()).isFalse();
    }

    @Test
    void trap4IndexIsProvenByItsPlanAndItsTime() {
        Verdict v = verdict("index-review-reviewer-name");
        assertThat(v.reasons()).isEmpty();
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        assertThat(v.report().path("schemaChanged").asBoolean()).isTrue();

        JsonNode plan = v.report().path("plans").get(0);
        assertThat(plan.path("before").asText()).contains("on review").contains("filter ((reviewer_name)");
        assertThat(plan.path("after").asText()).contains("idx_review_reviewer_name");

        // same statement, at least 20 % less SQL time
        JsonNode search = endpoint(v, "GET /reviews/search");
        assertThat(search.path("after").path("statementsPerRequest").asDouble())
                .isEqualTo(search.path("before").path("statementsPerRequest").asDouble());
        assertThat(search.path("after").path("sqlMsPerRequest").asDouble())
                .isLessThanOrEqualTo(search.path("before").path("sqlMsPerRequest").asDouble() * 0.8);
    }

    @Test
    void theIndexIsWrittenAndTheRepositoryIsUntouched() throws Exception {
        JsonNode index = new com.fasterxml.jackson.databind.ObjectMapper().readTree(OUT.resolve("index.json").toFile());
        assertThat(index).allMatch(e -> e.path("proposed").asBoolean());
        assertThat(REPO.resolve("demo-app/src/main/resources/db/changelog/002-index-review-reviewer-name.sql")).doesNotExist();
        assertThat(Files.readString(REPO.resolve(
                "demo-app/src/main/java/io/github/anthonydeseille/flairquery/demo/domain/Book.java")))
                .contains("    @ManyToOne\n    @JoinColumn(name = \"publisher_id\")");
    }

    @Test
    void scorecardEveryTrapInScopeIsFixedAndProven() {
        assertThat(scorecard).filteredOn(r -> r.trap() != null && r.trap() != 7)
                .allSatisfy(r -> assertThat(r.status()).as("trap " + r.trap() + ": " + r.detail())
                        .isEqualTo(TrapScorecard.Status.FIXED));
        assertThat(scorecard).filteredOn(r -> r.trap() != null && r.trap() == 7)
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(TrapScorecard.Status.OUT_OF_SCOPE));
        assertThat(scorecard.get(scorecard.size() - 1).status()).isEqualTo(TrapScorecard.Status.CLEAN);
        assertThat(TrapScorecard.summary(scorecard)).isEqualTo("6/6 traps fixed and proven");
        // traps 2 and 4 by the templates, 1, 3, 5, 6 by the LLM
        assertThat(scorecard).filteredOn(r -> r.status() == TrapScorecard.Status.FIXED)
                .extracting(TrapScorecard.Row::trap, TrapScorecard.Row::generator)
                .contains(org.assertj.core.groups.Tuple.tuple(2, "lazy-association"),
                        org.assertj.core.groups.Tuple.tuple(4, "index-migration"),
                        org.assertj.core.groups.Tuple.tuple(3, "llm"),
                        org.assertj.core.groups.Tuple.tuple(5, "llm"));
    }

    // ------------------------------------------------------------------------------------------------ phase 5

    static MrScorecard.Row mrRow(int trap) {
        return mrScorecard.stream().filter(r -> r.trap() != null && r.trap() == trap).findFirst().orElseThrow();
    }

    static String red(String fix) {
        for (JsonNode e : mrIndex) {
            if (e.path("fix").asText().equals(fix)) return e.path("red").asText();
        }
        throw new AssertionError("no merge request " + fix);
    }

    @Test
    void threeMergeRequestsTemplatesFirst() {
        assertThat(mrs).extracting(Description.MergeRequest::fix).containsExactly(
                "lazy-book-publisher", "index-review-reviewer-name", "llm-trap1-authors-list-title-projection");
    }

    @Test
    void trap2UselessEagerTestIsRedThenGreen() {
        assertThat(red("lazy-book-publisher")).contains("Book.publisher");
    }

    @Test
    void trap4MissingIndexTestIsRedThenGreen() {
        assertThat(red("index-review-reviewer-name")).contains("reviewer_name");
    }

    @Test
    void trap1NPlusOneTestIsRedThenGreen() {
        assertThat(red("llm-trap1-authors-list-title-projection")).contains("N+1");
    }

    @Test
    void scorecardTrapsProposedAsMergeRequests() {
        for (int trap : List.of(1, 2, 4, 6)) {
            assertThat(mrRow(trap)).as("trap " + trap).satisfies(r -> {
                assertThat(r.status()).isEqualTo(MrScorecard.Status.PROPOSED);
                assertThat(r.testProven()).as(r.test()).isTrue();
            });
        }
        assertThat(mrRow(6).fix()).isEqualTo("llm-trap1-authors-list-title-projection");
        assertThat(mrRow(6).detail()).contains("llm-trap6-authors-list-dto-projection-no-open-in-view REDUNDANT");
        for (int trap : List.of(3, 5)) {
            assertThat(mrRow(trap)).as("trap " + trap).satisfies(r -> {
                assertThat(r.status()).isEqualTo(MrScorecard.Status.DEFERRED);
                assertThat(r.detail()).contains("llm-trap1-authors-list-title-projection");
            });
        }
        assertThat(mrRow(7).status()).isEqualTo(MrScorecard.Status.OUT_OF_SCOPE);
        assertThat(mrScorecard.get(mrScorecard.size() - 1).status()).isEqualTo(MrScorecard.Status.CLEAN);
        assertThat(MrScorecard.summary(mrScorecard)).isEqualTo("4/6 traps proposed as merge requests "
                + "(3 merge requests, 3 with a regression test proven red then green), 2 deferred");
    }

    @Test
    void theMergeRequestsAndTheScorecardAreWritten() throws Exception {
        for (Description.MergeRequest mr : mrs) {
            Path patch = OUT.resolve("mr/" + mr.fix() + "/" + mr.fix() + ".patch");
            assertThat(Files.readString(patch)).contains("RegressionTest.java");
        }
        assertThat(Files.readString(OUT.resolve("mr/scorecard.md"))).contains("phase 5 scorecard");
        Process git = new ProcessBuilder("git", "status", "--porcelain", "demo-app/src", "demo-app/pom.xml").directory(REPO.toFile()).start();
        assertThat(new String(git.getInputStream().readAllBytes())).isEmpty();
    }
}
