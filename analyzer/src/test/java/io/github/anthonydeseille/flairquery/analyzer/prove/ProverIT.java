package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prover on the real demo app (built from a copy of its sources, run with the agent on a Testcontainers
 * PostgreSQL seeded with corpus S), with three hand-written patches for trap 2 ({@code USELESS_EAGER}
 * {@code Book.publisher} on {@code GET /books}): a good fix, a fix that changes the JSON, a fix that improves
 * nothing. Needs Docker, python3 and the agent jar.
 */
class ProverIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path PATCHES = Path.of("src/test/resources/patches").toAbsolutePath();
    static final String FINDING = BundleBuilder.id("USELESS_EAGER", "GET /books", "Book.publisher");

    static List<Verdict> verdicts;

    @BeforeAll
    static void prove() throws Exception {
        Path work = REPO.resolve("analyzer/target/prover-it");
        Prover.Config config = new Prover.Config(
                new Workspace.Config(REPO, "demo-app", work,
                        List.of(REPO.resolve("mvnw").toString(), "-B", "-q", "package", "-DskipTests"),
                        "target/flairquery-demo-app.jar"),
                new AppRun.Options(Path.of(System.getProperty("fq.agent.jar", "../agent/target/flairquery-agent.jar")),
                        "io.github.anthonydeseille.flairquery.demo", "/actuator/health", Duration.ofMinutes(3)),
                RequestSpec.read(REPO.resolve("analyzer/requests-demo.txt")),
                Seeder.parse("corpus:S", REPO.resolve("corpus")),
                5, "postgres:16", work.resolve("logs"), Judge.Thresholds.DEFAULT, false);
        verdicts = new Prover(config, m -> System.out.println("[prove] " + m)).prove(FINDING, List.of(
                PATCHES.resolve("publisher-lazy.patch"),
                PATCHES.resolve("books-projection-wrong-order.patch"),
                PATCHES.resolve("publisher-explicit-eager.patch")));
        ProveCommand.write(FINDING, List.of(PATCHES.resolve("publisher-lazy.patch"),
                PATCHES.resolve("books-projection-wrong-order.patch"),
                PATCHES.resolve("publisher-explicit-eager.patch")), verdicts, REPO.resolve("analyzer/target/proofs-it"));
    }

    static JsonNode check(Verdict v, String name) {
        return StreamSupport.stream(v.report().path("checks").spliterator(), false)
                .filter(c -> c.path("check").asText().equals(name)).findFirst().orElseThrow();
    }

    static JsonNode endpoint(Verdict v, String name) {
        return StreamSupport.stream(v.report().path("endpoints").spliterator(), false)
                .filter(c -> c.path("endpoint").asText().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void lazyPublisherIsAcceptedWithFewerStatementsAndLessSqlTime() {
        Verdict v = verdicts.get(0);
        assertThat(v.reasons()).isEmpty();
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);

        JsonNode books = endpoint(v, "GET /books");
        assertThat(books.path("after").path("statementsPerRequest").asDouble())
                .isLessThan(books.path("before").path("statementsPerRequest").asDouble());
        assertThat(books.path("after").path("statementsPerRequest").asDouble()).isEqualTo(1.0);
        // the same useless EAGER disappears from the other endpoints too
        assertThat(StreamSupport.stream(v.report().path("findingsResolved").spliterator(), false)
                .filter(f -> f.path("kind").asText().equals("USELESS_EAGER")).map(f -> f.path("endpoint").asText()))
                .contains("GET /books", "GET /authors/with-books");
        assertThat(v.report().path("findingsNew")).isEmpty();
        // untouched by the patch: same SQL, so its (noisy) time is not held against the fix
        assertThat(endpoint(v, "GET /reviews/ranking").path("sameSql").asBoolean()).isTrue();
        assertThat(endpoint(v, "GET /books").path("sameSql").asBoolean()).isFalse();
    }

    @Test
    void aFasterProjectionThatReordersTheBooksIsRejectedOnTheJson() {
        Verdict v = verdicts.get(1);
        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).anyMatch(r -> r.startsWith("response changed: /books?limit=50: JSON differs at /0/"));
        // it is cheaper and removes the finding: only the JSON check fails
        assertThat(check(v, "identical responses").path("passed").asBoolean()).isFalse();
        assertThat(check(v, "finding gone").path("passed").asBoolean()).isTrue();
        assertThat(check(v, "endpoint cheaper").path("passed").asBoolean()).isTrue();
    }

    @Test
    void anExplicitEagerIsRejectedAsUseless() {
        Verdict v = verdicts.get(2);
        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).contains("the finding is still reported");
        assertThat(v.reasons()).anyMatch(r -> r.startsWith("no measurable gain: GET /books"));
        assertThat(check(v, "identical responses").path("passed").asBoolean()).isTrue();
    }

    @Test
    void theRepositoryIsUntouchedAndTheCopiesAreRemoved() throws Exception {
        assertThat(Files.readString(REPO.resolve(
                "demo-app/src/main/java/io/github/anthonydeseille/flairquery/demo/domain/Book.java")))
                .contains("    @ManyToOne\n    @JoinColumn(name = \"publisher_id\")");
        assertThat(REPO.resolve("analyzer/target/prover-it/baseline")).doesNotExist();
    }
}
