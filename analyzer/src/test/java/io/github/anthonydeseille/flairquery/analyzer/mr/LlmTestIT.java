package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.fix.FixCommand;
import io.github.anthonydeseille.flairquery.analyzer.fix.GeneratorsTestAccess;
import io.github.anthonydeseille.flairquery.analyzer.llm.ClaudeClient;
import io.github.anthonydeseille.flairquery.analyzer.prove.RequestSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5 step 2 end to end with real API calls: Claude writes the regression tests of the three demo merge
 * requests (traps 2, 4 and 1) and each is proven red / green by Maven (retried with the reasons when not). The
 * proofs of the fixes are not replayed: a minimal report (finding, target endpoint before / after, as measured by
 * {@code FixIT}) stands for them. Opt-in ({@code make llm-it}), skipped without {@code ANTHROPIC_API_KEY}. The
 * proven tests are written to {@code analyzer/target/llm-tests-it}: copy them to
 * {@code src/test/resources/regression-tests/<fix>--<Class>.java} to refresh the references of {@link RegressionTestIT}.
 */
class LlmTestIT {

    static final Path REPO = RegressionTestIT.REPO;
    static final Path OUT = REPO.resolve("analyzer/target/llm-tests-it");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, RegressionTests.Outcome> outcomes = new LinkedHashMap<>();

    @BeforeAll
    static void generateAndProve() throws Exception {
        assumeTrue(Boolean.getBoolean("fq.llm.it"), "real LLM calls are opt-in: make llm-it (-Dfq.llm.it=true)");
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set: no real LLM calls");
        Files.createDirectories(OUT);
        Map<String, String> patches = new LinkedHashMap<>();
        GeneratorsTestAccess.bundles();
        for (FixCommand.Proposal p : FixCommand.generate(GeneratorsTestAccess.all(), REPO.resolve("demo-app"), "demo-app",
                FixCommand.generators(), s -> { })) {
            patches.put(p.fix().name(), p.patch());
        }
        patches.put("llm-trap1-authors-list-title-projection",
                Files.readString(RegressionTestIT.FIXES.resolve("trap1-authors-list-title-projection.patch")));

        LlmTestGenerator generator = new LlmTestGenerator(new ClaudeClient(null, "high", 64_000),
                FixCommand.describe(RequestSpec.read(REPO.resolve("analyzer/requests-demo.txt"))), 20, System.out::println);
        TestProof proof = new TestProof.Maven(REPO, "demo-app", OUT.resolve("runs"), List.of(REPO.resolve("mvnw").toString()));
        for (Case c : CASES) {
            Path patch = Files.writeString(OUT.resolve(c.fix() + ".patch"), patches.get(c.fix()));
            FixEntry fix = new FixEntry(c.fix(), c.fix().startsWith("llm-") ? "llm" : "template", c.description(),
                    List.of(c.finding()), 1, patch, "ACCEPTED", true, List.of(c.finding()), null);
            RegressionTests.Outcome o = RegressionTests.of(fix, patches.get(c.fix()), c.report(), REPO.resolve("demo-app"),
                    generator, proof, 2, System.out::println);
            outcomes.put(c.fix(), o);
            if (o.test() != null) {
                Files.writeString(OUT.resolve(c.fix() + "--" + o.test().simpleName() + ".java"), o.test().content());
            }
        }
        System.out.println("LLM usage: " + generator.usage());
    }

    record Case(String fix, String description, String finding, String kind, String endpoint, String subject,
                double before, double after, String title) {
        ObjectNode report() {
            ObjectNode r = JSON.createObjectNode().put("finding", finding).put("kind", kind).put("endpoint", endpoint)
                    .put("subject", subject);
            r.putArray("findingsResolved").addObject().put("id", finding).put("kind", kind).put("endpoint", endpoint)
                    .put("title", title);
            ObjectNode e = r.putArray("endpoints").addObject().put("endpoint", endpoint).put("target", true);
            e.putObject("before").put("statementsPerRequest", before).put("sqlMsPerRequest", 0);
            e.putObject("after").put("statementsPerRequest", after).put("sqlMsPerRequest", 0);
            return r;
        }
    }

    static final List<Case> CASES = List.of(
            new Case("lazy-book-publisher", "Book.publisher: @ManyToOne loaded EAGERly (JPA default) and never read "
                    + "-> fetch = FetchType.LAZY", "useless-eager-9662f1d7", "USELESS_EAGER", "GET /authors/with-books",
                    "Book.publisher", 326, 2, "Book.publisher is loaded EAGERly but never read"),
            new Case("index-review-reviewer-name", "review.reviewer_name: idx_review_reviewer_name built CONCURRENTLY "
                    + "by a new Liquibase changeset", "missing-index-8ed01c2b", "MISSING_INDEX", "GET /reviews/search",
                    "review.reviewer_name", 1, 1, "No index for review.reviewer_name: every call scans review"),
            new Case("llm-trap1-authors-list-title-projection", "GET /authors from two projections: the page of "
                    + "authors, then the titles of their books in one query", "n-plus-one-lazy-947116bb",
                    "N_PLUS_ONE_LAZY", "GET /authors", "Author.books", 22, 2,
                    "N+1: Author.books is loaded lazily 20 times per request"));

    @Test
    void everyMergeRequestGetsATestProvenRedThenGreen() {
        assertThat(outcomes).hasSize(3).allSatisfy((fix, o) -> {
            assertThat(o.missing()).as(fix).isNull();
            assertThat(o.red()).as(fix).isNotBlank();
        });
    }
}
