package io.github.anthonydeseille.flairquery.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.code.SourceLocator;
import io.github.anthonydeseille.flairquery.analyzer.db.DatabaseFindings;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence bundles from real responses (agent + collector recorded on profile M, value-free) and the real
 * demo app sources.
 */
class BundleTest {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    static final String DEMO = "io.github.anthonydeseille.flairquery.demo.";
    static SourceLocator sources;
    static JsonSource agent;
    static JsonSource collector;

    @BeforeAll
    static void load() {
        sources = new SourceLocator(Path.of(System.getProperty("fq.demo.sources", "../demo-app")));
        agent = JsonSource.files(Map.of(
                "/fq/findings", FIXTURES.resolve("agent-findings.json"),
                "/fq/model", FIXTURES.resolve("agent-model.json")), null);
        collector = JsonSource.files(Map.of(
                "/fq/collector/queries", FIXTURES.resolve("collector-queries.json"),
                "/fq/collector/columns", FIXTURES.resolve("collector-columns.json")),
                path -> path.contains("queryid=")
                        ? FIXTURES.resolve("collector-plans-" + path.substring(path.indexOf("queryid=") + 8) + ".json") : null);
    }

    // ---------------------------------------------------------------- database side

    @Test
    void databaseFindingsCoverTheMissingIndexAndTheSortSpill() throws Exception {
        List<ObjectNode> f = DatabaseFindings.of(collector, "1h");

        assertThat(f).extracting(x -> x.path("kind").asText() + " " + x.path("endpoint").asText())
                .containsExactlyInAnyOrder("MISSING_INDEX GET /reviews/search", "SORT_SPILL GET /reviews/ranking");
        JsonNode index = f.stream().filter(x -> x.path("kind").asText().equals("MISSING_INDEX")).findFirst().orElseThrow().path("evidence");
        assertThat(index.path("table").asText()).isEqualTo("review");
        assertThat(index.path("column").asText()).isEqualTo("reviewer_name");
        assertThat(index.path("caller").asText()).startsWith(DEMO + "web.ReviewController#search:");
        JsonNode sort = f.stream().filter(x -> x.path("kind").asText().equals("SORT_SPILL")).findFirst().orElseThrow().path("evidence");
        assertThat(sort.path("sortKeys").toString()).contains("created_at");
    }

    // ---------------------------------------------------------------- source locator

    @Test
    void locatesClassesCallersRepositoriesTablesMigrationsAndConfiguration() {
        assertThat(sources.locate(DEMO + "domain.Book")).hasValueSatisfying(p -> assertThat(p).hasFileName("Book.java"));
        var caller = sources.caller(DEMO + "web.AuthorController#toDto:47").orElseThrow();
        assertThat(caller.line()).isEqualTo(47);
        // books.size() initializes the lazy collection: the agent's caller line is the line that triggered the load
        assertThat(sources.read(caller.file()).lines().skip(46).findFirst().orElse("")).contains("books.size()");
        assertThat(sources.repositoriesOf("Book")).containsExactly(DEMO + "repository.BookRepository");
        assertThat(sources.repositoriesOf(DEMO + "domain.Author")).containsExactly(DEMO + "repository.AuthorRepository");
        assertThat(sources.entityOfTable("review")).hasValue(DEMO + "domain.Review");
        assertThat(sources.migrations()).first().satisfies(p -> assertThat(p.getFileName().toString()).contains("master"));
        assertThat(sources.configuration()).extracting(p -> p.getFileName().toString()).contains("application.yml");
        assertThat(sources.locate("com.unknown.Nope")).isEmpty();
    }

    @Test
    void buildOutputsAndCompiledClassesAreNotIndexed(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/a"));
        Files.writeString(src.resolve("A.java"), "package a; public class A { }");
        Path target = Files.createDirectories(tmp.resolve("target/generated-sources/a"));
        Files.writeString(target.resolve("B.java"), "package a; public class B { }");
        SourceLocator l = new SourceLocator(tmp);
        assertThat(l.locate("a.A")).isPresent();
        assertThat(l.locate("a.B")).isEmpty();
    }

    // ---------------------------------------------------------------- bundles

    static List<ObjectNode> bundles() throws Exception {
        return new BundleBuilder(agent, collector, sources, "1h").build();
    }

    static ObjectNode bundle(List<ObjectNode> all, String kind, String endpoint) {
        return all.stream().filter(b -> b.path("kind").asText().equals(kind) && b.path("endpoint").asText().equals(endpoint))
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind + " bundle on " + endpoint));
    }

    static List<String> sourceRoles(JsonNode b) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : b.path("sources")) {
            String file = Path.of(s.path("path").asText()).getFileName().toString();
            s.path("roles").forEach(r -> out.add(r.asText() + ":" + file));
        }
        return out;
    }

    @Test
    void oneBundlePerFindingWithStableUniqueIds() throws Exception {
        List<ObjectNode> first = bundles();
        List<ObjectNode> second = bundles();

        assertThat(first).hasSize(13 + 2);                       // agent findings + database findings
        assertThat(first).extracting(b -> b.path("id").asText()).doesNotHaveDuplicates()
                .containsExactlyElementsOf(second.stream().map(b -> b.path("id").asText()).toList());
        assertThat(first).allSatisfy(b -> assertThat(b.path("sources")).isNotEmpty());
    }

    @Test
    void nPlusOneBundleHasTheEntitiesTheCallerAndTheRepository() throws Exception {
        ObjectNode b = bundle(bundles(), "N_PLUS_ONE_LAZY", "GET /authors");

        assertThat(sourceRoles(b)).contains("entity:Author.java", "entity:Book.java", "caller:AuthorController.java",
                "repository:AuthorRepository.java");
        JsonNode caller = StreamSupport.stream(b.path("sources").spliterator(), false)
                .filter(s -> s.path("path").asText().endsWith("AuthorController.java")).findFirst().orElseThrow();
        assertThat(caller.path("line").asInt()).isEqualTo(47);
        assertThat(caller.path("content").asText()).contains("a.getBooks()");
        assertThat(b.path("database").path("statements")).anySatisfy(s ->
                assertThat(s.path("sql").asText()).contains("from book b1_0", "where b1_0.author_id=$1"));
    }

    @Test
    void eagerOverFetchingOpenInViewAndIndexBundlesPointAtWhatAFixTouches() throws Exception {
        List<ObjectNode> all = bundles();

        assertThat(sourceRoles(bundle(all, "USELESS_EAGER", "GET /books")))
                .contains("entity:Book.java", "entity:Publisher.java", "repository:BookRepository.java");
        assertThat(sourceRoles(bundle(all, "OVER_FETCHING", "GET /books/titles")))
                .contains("entity:Book.java", "repository:BookRepository.java", "caller:BookService.java");
        assertThat(sourceRoles(bundle(all, "OPEN_IN_VIEW", "GET /authors")))
                .contains("caller:AuthorController.java", "configuration:application.yml");
        assertThat(sourceRoles(bundle(all, "MISSING_INDEX", "GET /reviews/search")))
                .contains("entity:Review.java", "caller:ReviewController.java", "migration:db.changelog-master.yaml",
                        "migration:001-schema.sql");
        assertThat(sourceRoles(bundle(all, "IN_MEMORY_PAGINATION", "GET /authors/with-books")))
                .contains("caller:AuthorService.java", "repository:AuthorRepository.java");
    }
}
