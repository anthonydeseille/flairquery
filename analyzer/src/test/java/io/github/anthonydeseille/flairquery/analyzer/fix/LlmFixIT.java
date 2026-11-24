package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.llm.ClaudeClient;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;
import io.github.anthonydeseille.flairquery.analyzer.prove.Prover;
import io.github.anthonydeseille.flairquery.analyzer.prove.RequestSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 step 4 end to end: Claude writes the fixes of traps 1 (N+1), 3 (over-fetching), 5 (JOIN FETCH +
 * pagination) and 6 (open-in-view) from the recorded bundles and the real demo sources; the prover proves them on
 * the real demo app (Testcontainers PostgreSQL, corpus S, agent attached), and a rejected fix is retried with the
 * prover's reasons. Real API calls (a few dollars per run): opt-in ({@code make llm-it}, {@code -Dfq.llm.it=true})
 * and skipped without {@code ANTHROPIC_API_KEY}, so {@code make verify} costs nothing. Needs Docker, python3 and the
 * agent jar.
 */
class LlmFixIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path OUT = REPO.resolve("analyzer/target/llm-fixes-it");
    static final String N_PLUS_ONE = BundleBuilder.id("N_PLUS_ONE_LAZY", "GET /authors", "Author.books");
    static final String OVER_FETCHING = BundleBuilder.id("OVER_FETCHING", "GET /books/titles",
            "io.github.anthonydeseille.flairquery.demo.domain.Book");
    static final String PAGINATION = BundleBuilder.id("IN_MEMORY_PAGINATION", "GET /authors/with-books", "");
    static final String OPEN_IN_VIEW = BundleBuilder.id("OPEN_IN_VIEW", "GET /authors", "");
    static final Set<String> TRAPS = Set.of(N_PLUS_ONE, OVER_FETCHING, PAGINATION, OPEN_IN_VIEW);

    static List<FixCommand.Outcome> outcomes;
    static LlmClient.Usage usage;

    @BeforeAll
    static void generateAndProve() throws Exception {
        assumeTrue(Boolean.getBoolean("fq.llm.it"), "real LLM calls are opt-in: make llm-it (-Dfq.llm.it=true)");
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set: no real LLM calls");
        GeneratorsTest.bundles();
        List<JsonNode> bundles = GeneratorsTest.bundles.stream()
                .filter(b -> TRAPS.contains(b.path("id").asText())).map(b -> (JsonNode) b).toList();
        assertThat(bundles).hasSize(4);

        Path app = REPO.resolve("demo-app");
        LlmFixGenerator llm = new LlmFixGenerator(new ClaudeClient(null, "high", 64_000),
                FixCommand.describe(RequestSpec.read(REPO.resolve("analyzer/requests-demo.txt"))), 20, System.out::println);
        List<FixCommand.Proposal> proposals = FixCommand.generate(bundles, app, "demo-app", FixCommand.generators(), llm,
                System.out::println);
        Prover.Config config = ProveCommand.config(Map.of(
                "repo", REPO.toString(),
                "agent-jar", System.getProperty("fq.agent.jar", REPO.resolve("agent/target/flairquery-agent.jar").toString()),
                "work", REPO.resolve("analyzer/target/prover-llm-it").toString()));
        outcomes = FixCommand.proveAndRetry(proposals, app, "demo-app", llm, 2,
                (p, patches) -> FixCommand.prove(config, p, patches), OUT, System.out::println);
        FixCommand.writeIndex(outcomes, OUT);
        usage = llm.usage();
        System.out.println("LLM usage: " + usage);
    }

    /** The finding is resolved by a proven fix written by the LLM (possibly after retries). */
    static void proven(String finding) {
        assertThat(outcomes).filteredOn(o -> o.verdict() != null && o.verdict().accepted())
                .filteredOn(o -> o.proposal().fix().generator().equals("llm"))
                .anySatisfy(o -> assertThat(StreamSupport.stream(o.verdict().report().path("findingsResolved").spliterator(), false)
                        .map(f -> f.path("id").asText())).contains(finding));
    }

    @Test
    void trap1NPlusOneIsFixedAndProven() {
        proven(N_PLUS_ONE);
    }

    @Test
    void trap3OverFetchingIsFixedAndProven() {
        proven(OVER_FETCHING);
    }

    @Test
    void trap5InMemoryPaginationIsFixedAndProven() {
        proven(PAGINATION);
    }

    @Test
    void trap6OpenInViewIsFixedAndProven() {
        proven(OPEN_IN_VIEW);
    }

    @Test
    void onlyProvenFixesAreProposedAndTheDemoIsUntouched() throws Exception {
        JsonNode index = new com.fasterxml.jackson.databind.ObjectMapper().readTree(OUT.resolve("index.json").toFile());
        assertThat(index).allMatch(e -> e.path("proposed").asBoolean() == e.path("status").asText().equals("ACCEPTED"));
        ProcessBuilder git = new ProcessBuilder("git", "status", "--porcelain", "demo-app").directory(REPO.toFile());
        Process p = git.start();
        assertThat(new String(p.getInputStream().readAllBytes())).isEmpty();
        assertThat(Files.readString(REPO.resolve("demo-app/src/main/resources/application.yml")))
                .doesNotContain("open-in-view: false");
    }
}
