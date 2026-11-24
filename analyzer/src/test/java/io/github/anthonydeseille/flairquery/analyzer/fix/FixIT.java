package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
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
 * Phase 4 step 3 end to end: the deterministic generators turn the bundles (recorded agent + collector
 * responses, real demo sources) into patches, and the prover proves them on the real demo app (Testcontainers
 * PostgreSQL seeded with corpus S, agent attached): trap 2 by fewer statements, trap 4 by a plan that uses the
 * new index and less SQL time. Needs Docker, python3 and the agent jar.
 */
class FixIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path OUT = REPO.resolve("analyzer/target/fixes-it");
    static List<FixCommand.Proposal> proposals;
    static List<Verdict> verdicts;

    @BeforeAll
    static void generateAndProve() throws Exception {
        GeneratorsTest.bundles();
        List<JsonNode> bundles = new ArrayList<>(GeneratorsTest.bundles);
        proposals = FixCommand.generate(bundles, REPO.resolve("demo-app"), "demo-app", FixCommand.generators(), System.out::println);
        List<Path> patches = FixCommand.writePatches(proposals, OUT);
        verdicts = FixCommand.prove(ProveCommand.config(Map.of(
                "repo", REPO.toString(),
                "agent-jar", System.getProperty("fq.agent.jar", REPO.resolve("agent/target/flairquery-agent.jar").toString()),
                "work", REPO.resolve("analyzer/target/prover-fix-it").toString())), proposals, patches);
        FixCommand.writeIndex(proposals, patches, verdicts, OUT);
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
        assertThat(proposals).extracting(p -> p.fix().name())
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
}
