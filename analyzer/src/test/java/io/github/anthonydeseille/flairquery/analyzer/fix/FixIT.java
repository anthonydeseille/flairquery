package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
import io.github.anthonydeseille.flairquery.analyzer.prove.Prover;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 end to end, and its trap scorecard: the deterministic generators turn the bundles (recorded agent +
 * collector responses, real demo sources) into patches (traps 2 and 4), the LLM fixes accepted by
 * {@link LlmFixIT} are replayed as frozen reference patches (traps 1, 3, 5, 6: no API call here), and the prover
 * proves all of them against one baseline on the real demo app (Testcontainers PostgreSQL seeded with corpus S,
 * agent attached). Every trap in scope must be FIXED, the witness CLEAN. When the demo changes and a reference
 * patch no longer applies or proves, regenerate it with {@code make llm-it} (copy the accepted patches of
 * {@code analyzer/target/llm-fixes-it} into {@code src/test/resources/fixes}). Needs Docker, python3 and the
 * agent jar.
 */
class FixIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path OUT = REPO.resolve("analyzer/target/fixes-it");
    static final Path REFERENCES = Path.of("src/test/resources/fixes");
    static List<FixCommand.Proposal> generated;
    static List<FixCommand.Proposal> proposals;
    static List<Verdict> verdicts;
    static List<TrapScorecard.Row> scorecard;

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
}
