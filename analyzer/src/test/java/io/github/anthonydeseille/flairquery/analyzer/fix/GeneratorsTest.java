package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.code.SourceLocator;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic generators on bundles built from the recorded agent + collector responses and the real
 * demo app sources, plus the source shapes the demo does not have.
 */
class GeneratorsTest {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    static final Path DEMO = Path.of(System.getProperty("fq.demo.sources", "../demo-app")).toAbsolutePath().normalize();
    static final String BOOK = "src/main/java/io/github/anthonydeseille/flairquery/demo/domain/Book.java";
    static final String MASTER = "src/main/resources/db/changelog/db.changelog-master.yaml";
    static final ObjectMapper JSON = new ObjectMapper();
    static List<ObjectNode> bundles;

    @TempDir
    Path tmp;

    @BeforeAll
    static synchronized void bundles() throws Exception {
        if (bundles != null) return;
        JsonSource agent = JsonSource.files(Map.of(
                "/fq/findings", FIXTURES.resolve("agent-findings.json"),
                "/fq/model", FIXTURES.resolve("agent-model.json")), null);
        JsonSource collector = JsonSource.files(Map.of(
                "/fq/collector/queries", FIXTURES.resolve("collector-queries.json"),
                "/fq/collector/columns", FIXTURES.resolve("collector-columns.json")),
                path -> path.contains("queryid=")
                        ? FIXTURES.resolve("collector-plans-" + path.substring(path.indexOf("queryid=") + 8) + ".json") : null);
        bundles = new BundleBuilder(agent, collector, new SourceLocator(DEMO), "1h").build();
    }

    static List<JsonNode> ofKind(String kind) {
        return bundles.stream().filter(b -> b.path("kind").asText().equals(kind)).map(b -> (JsonNode) b).toList();
    }

    // ---------------------------------------------------------------- trap 2

    @Test
    void uselessEagerBecomesLazyOnTheFieldOnly() throws Exception {
        JsonNode books = ofKind("USELESS_EAGER").stream()
                .filter(b -> b.path("endpoint").asText().equals("GET /books")).findFirst().orElseThrow();
        Fix fix = new LazyAssociationFix().generate(books, DEMO).fix();

        assertThat(fix.name()).isEqualTo("lazy-book-publisher");
        assertThat(fix.files()).containsOnlyKeys(BOOK);
        String patch = fix.patch("demo-app");
        // same change as the hand-written patch the prover accepted in step 2
        assertThat(patch).contains("--- a/demo-app/" + BOOK + "\n+++ b/demo-app/" + BOOK + "\n")
                .contains("-    @ManyToOne\n+    @ManyToOne(fetch = FetchType.LAZY)\n     @JoinColumn(name = \"publisher_id\")");
        // the javadoc above names @ManyToOne too: untouched; FetchType is already imported
        assertThat(patch.lines().filter(l -> l.startsWith("+") || l.startsWith("-"))).hasSize(4);
        assertThat(applies(patch)).isTrue();
    }

    @Test
    void theSameCauseOnSeveralEndpointsIsOneProposal() throws Exception {
        List<FixCommand.Proposal> proposals = FixCommand.generate(ofKind("USELESS_EAGER"), DEMO, "demo-app",
                FixCommand.generators(), s -> { });
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).findings()).hasSize(4).contains(BundleBuilder.id("USELESS_EAGER", "GET /books", "Book.publisher"));
    }

    @Test
    void annotationShapes() throws Exception {
        assertThat(lazy("@ManyToOne(optional = false)", "import jakarta.persistence.ManyToOne;\n"))
                .contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)")
                .contains("import jakarta.persistence.FetchType;\nimport jakarta.persistence.ManyToOne;\n");
        assertThat(lazy("@OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)",
                "import javax.persistence.*;\n"))
                .contains("@OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)")
                .doesNotContain("import javax.persistence.FetchType;");
        assertThat(lazy("@ManyToOne()", "import javax.persistence.Id;\nimport javax.persistence.ManyToOne;\n"))
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("import javax.persistence.FetchType;\nimport javax.persistence.Id;\nimport javax.persistence.ManyToOne;\n");
        assertThat(lazy("@jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)", ""))
                .contains("@jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)")
                .doesNotContain("import jakarta.persistence.FetchType;");
    }

    @Test
    void notGeneratedWhenTheFieldCannotBeFound() throws Exception {
        Path app = entity("@ManyToOne", "import jakarta.persistence.ManyToOne;\n");
        FixGenerator.Result r = new LazyAssociationFix().generate(bundle("Shop.owner", app), app);
        assertThat(r.fix()).isNull();
        assertThat(r.skipped()).contains("Shop.owner: field not found");
    }

    private String lazy(String annotation, String imports) throws Exception {
        Path app = entity(annotation, imports);
        FixGenerator.Result r = new LazyAssociationFix().generate(bundle("Shop.publisher", app), app);
        assertThat(r.skipped()).isNull();
        return r.fix().files().values().iterator().next().after();
    }

    private Path entity(String annotation, String imports) throws Exception {
        Path app = Files.createTempDirectory(tmp, "app");
        Path file = app.resolve("src/main/java/x/Shop.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package x;\n\n" + imports + "\n@Entity\npublic class Shop {\n\n"
                + "    /** loaded with {@code @ManyToOne} by default; */\n"
                + "    " + annotation + "\n    @JoinColumn(name = \"publisher_id\")\n    private Publisher publisher;\n\n"
                + "    public Publisher getPublisher() { return publisher; }\n}\n");
        return app;
    }

    private static JsonNode bundle(String association, Path app) {
        ObjectNode b = JSON.createObjectNode().put("id", "useless-eager-test").put("kind", "USELESS_EAGER")
                .put("endpoint", "GET /shops");
        b.putObject("evidence").put("association", association);
        b.putArray("sources").addObject().put("class", "x.Shop").put("path", "src/main/java/x/Shop.java")
                .putArray("roles").add("entity");
        return b;
    }

    // ---------------------------------------------------------------- trap 4

    @Test
    void missingIndexBecomesAConcurrentLiquibaseChangeset() throws Exception {
        JsonNode bundle = ofKind("MISSING_INDEX").get(0);
        Fix fix = new IndexMigrationFix().generate(bundle, DEMO).fix();

        String file = "src/main/resources/db/changelog/002-index-review-reviewer-name.sql";
        assertThat(fix.files()).containsOnlyKeys(file, MASTER);
        assertThat(fix.files().get(file).before()).isNull();
        assertThat(fix.files().get(file).after())
                .startsWith("--liquibase formatted sql\n")
                .contains("--changeset flairquery:002-index-review-reviewer_name runInTransaction:false\n")
                .contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_review_reviewer_name ON review (reviewer_name);\n")
                .contains("--rollback DROP INDEX CONCURRENTLY IF EXISTS idx_review_reviewer_name;")
                .contains(bundle.path("id").asText());
        assertThat(fix.files().get(MASTER).after())
                .endsWith("  - include:\n      file: db/changelog/001-schema.sql\n"
                        + "  - include:\n      file: db/changelog/002-index-review-reviewer-name.sql\n");
        assertThat(applies(fix.patch("demo-app"))).isTrue();
    }

    @Test
    void xmlMasterChangelog() throws Exception {
        Path app = Files.createTempDirectory(tmp, "app");
        Path dir = app.resolve("src/main/resources/db/changelog");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("db.changelog-master.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <include file="db/changelog/changes/007-orders.sql"/>
                </databaseChangeLog>
                """);
        Files.createDirectories(dir.resolve("changes"));
        Files.writeString(dir.resolve("changes/007-orders.sql"), "--liquibase formatted sql\n");
        ObjectNode b = JSON.createObjectNode().put("id", "missing-index-test").put("kind", "MISSING_INDEX")
                .put("endpoint", "GET /orders").put("title", "No index for orders.customer_ref");
        b.putObject("evidence").put("table", "orders").put("column", "customer_ref");
        var sources = b.putArray("sources");
        sources.addObject().put("path", "src/main/resources/db/changelog/db.changelog-master.xml").putArray("roles").add("migration");
        sources.addObject().put("path", "src/main/resources/db/changelog/changes/007-orders.sql").putArray("roles").add("migration");

        Fix fix = new IndexMigrationFix().generate(b, app).fix();
        assertThat(fix.files()).containsKey("src/main/resources/db/changelog/changes/008-index-orders-customer-ref.sql");
        assertThat(fix.files().get("src/main/resources/db/changelog/db.changelog-master.xml").after()).contains(
                "    <include file=\"db/changelog/changes/007-orders.sql\"/>\n"
                        + "    <include file=\"db/changelog/changes/008-index-orders-customer-ref.sql\"/>\n</databaseChangeLog>");
    }

    @Test
    void flywayIsNotHandledYet() throws Exception {
        ObjectNode b = JSON.createObjectNode().put("id", "missing-index-test").put("kind", "MISSING_INDEX");
        b.putObject("evidence").put("table", "orders").put("column", "customer_ref");
        b.putArray("sources").addObject().put("path", "src/main/resources/db/migration/V1__init.sql").putArray("roles").add("migration");
        assertThat(new IndexMigrationFix().generate(b, tmp).skipped()).contains("Flyway is not handled yet");
    }

    @Test
    void otherFindingsHaveNoDeterministicGenerator() throws Exception {
        List<String> log = new ArrayList<>();
        FixCommand.generate(new ArrayList<>(bundles), DEMO, "demo-app", FixCommand.generators(), log::add);
        assertThat(log).filteredOn(l -> l.startsWith("generated")).hasSize(5);
        assertThat(log).filteredOn(l -> l.startsWith("no-generator"))
                .allMatch(l -> l.matches(".*(N_PLUS_ONE_LAZY|OVER_FETCHING|OPEN_IN_VIEW|IN_MEMORY_PAGINATION|SORT_SPILL)$"));
    }

    /** {@code git apply --check} on a copy of the demo app at {@code <tmp>/demo-app}. */
    private boolean applies(String patch) throws Exception {
        Path root = Files.createTempDirectory(tmp, "repo");
        Path app = root.resolve("demo-app");
        for (String f : List.of(BOOK, MASTER, "src/main/resources/db/changelog/001-schema.sql")) {
            Files.createDirectories(app.resolve(f).getParent());
            Files.copy(DEMO.resolve(f), app.resolve(f));
        }
        Path p = root.resolve("fix.patch");
        Files.writeString(p, patch);
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--check", p.toString()).directory(root.toFile()).inheritIO();
        pb.environment().put("GIT_CEILING_DIRECTORIES", root.getParent().toString());
        return pb.start().waitFor() == 0;
    }
}
