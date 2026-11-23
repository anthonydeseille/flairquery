package io.github.anthonydeseille.flairquery.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3 hooks (Hibernate + Spring transactions) on the real demo app: /fq/profile. */
class JpaProfileIT {

    private static final String DEMO = "io.github.anthonydeseille.flairquery.demo";
    private static DemoAppProcess app;

    @BeforeAll
    static void start() throws Exception {
        app = DemoAppProcess.start("profile", true, DemoAppProcess.env());
        ItEnvironment.ensureSeeded();
        Traffic.run(app, 2);
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) app.close();
    }

    static JsonNode profile(String endpoint) throws Exception {
        return StreamSupport.stream(app.agentJson("/fq/profile").get("profiles").spliterator(), false)
                .filter(p -> p.get("endpoint").asText().equals(endpoint)).findFirst()
                .orElseThrow(() -> new AssertionError("no profile for " + endpoint));
    }

    static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get(field).asText().equals(value)).findFirst()
                .orElseThrow(() -> new AssertionError(value + " not in " + array));
    }

    @Test
    void authorsBooksAreLazyLoadedOutsideAnyTransactionThroughOpenInView() throws Exception {
        JsonNode p = profile("GET /authors");
        JsonNode books = find(p.get("lazyLoads"), "role", DEMO + ".domain.Author.books");

        assertThat(p.get("profiledRequests").asLong()).isEqualTo(2);
        assertThat(books.get("kind").asText()).isEqualTo("collection");
        assertThat(books.get("perRequest").asDouble()).isEqualTo(20.0);          // trap 1: one per author
        assertThat(books.get("outsideTransaction").asLong()).isEqualTo(40);     // trap 6: in the web layer
        assertThat(books.get("caller").asText()).startsWith(DEMO + ".web.AuthorController#toDto:");
        assertThat(p.get("openInViewRequests").asLong()).isEqualTo(2);
        assertThat(find(p.get("entities"), "entity", DEMO + ".domain.Author").get("perRequest").asDouble())
                .isEqualTo(20.0);
    }

    @Test
    void theNPlusOneStatementsAreTracedBackToTheLazyCollection() throws Exception {
        JsonNode p = profile("GET /authors");
        JsonNode perAuthor = StreamSupport.stream(p.get("statements").spliterator(), false)
                .filter(s -> s.get("sql").asText().contains("from book b1_0")).findFirst().orElseThrow();

        assertThat(perAuthor.get("perRequest").asDouble()).isEqualTo(20.0);
        assertThat(perAuthor.get("triggeredByLazyLoad").asText()).isEqualTo(DEMO + ".domain.Author.books");
        assertThat(p.get("queriesPerRequest").asDouble()).isEqualTo(22.0);   // page + count + 20 x books
        assertThat(p.get("rowsPerRequest").asDouble()).isPositive();
        JsonNode page = StreamSupport.stream(p.get("statements").spliterator(), false)
                .filter(s -> s.get("sql").asText().contains("from author a1_0 order by")).findFirst().orElseThrow();
        assertThat(page.get("rowsPerExecution").asDouble()).isEqualTo(20.0);  // limit=20
        assertThat(page.get("triggeredByLazyLoad").isNull()).isTrue();
    }

    @Test
    void joinFetchReadsEveryRowOfTheCollectionForAPageOfFive() throws Exception {
        JsonNode p = profile("GET /authors/with-books");
        JsonNode fetch = StreamSupport.stream(p.get("statements").spliterator(), false)
                .filter(s -> s.get("sql").asText().contains("join book b1_0")).findFirst().orElseThrow();
        assertThat(fetch.get("rowsPerExecution").asDouble()).isGreaterThan(5.0);   // trap 5: whole result read
    }

    @Test
    void eagerPublishersAreLoadedWithBooks() throws Exception {
        JsonNode p = profile("GET /books");
        assertThat(find(p.get("entities"), "entity", DEMO + ".domain.Book").get("perRequest").asDouble())
                .isEqualTo(50.0);
        assertThat(find(p.get("entities"), "entity", DEMO + ".domain.Publisher").get("perRequest").asDouble())
                .isPositive();                                                   // trap 2: EAGER by default
        assertThat(p.get("lazyLoads")).isEmpty();
    }

    @Test
    void joinFetchWithPaginationIsSeen() throws Exception {
        assertThat(profile("GET /authors/with-books").get("inMemoryPaginationRequests").asLong()).isEqualTo(2);
        assertThat(profile("GET /books").get("inMemoryPaginationRequests").asLong()).isZero();
    }

    static JsonNode entity(JsonNode profile, String simpleName) {
        return find(profile.get("entities"), "entity", DEMO + ".domain." + simpleName);
    }

    static java.util.List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void booksReadFourColumnsAndNeverTheEagerPublisher() throws Exception {
        JsonNode book = entity(profile("GET /books"), "Book");             // BookDto(id, title, isbn, year)
        assertThat(texts(book.get("neverRead"))).containsExactlyInAnyOrder("description", "author", "publisher");
        JsonNode publisher = find(book.get("attributes"), "name", "publisher");
        assertThat(publisher.get("fetch").asText()).isEqualTo("EAGER");
        assertThat(publisher.get("fetchExplicit").asBoolean()).isFalse();  // trap 2: EAGER by default, never read
        assertThat(find(book.get("attributes"), "name", "description").get("large").asBoolean()).isTrue();
    }

    @Test
    void titlesReadOnlyIdAndTitle() throws Exception {
        JsonNode book = entity(profile("GET /books/titles"), "Book");      // BookTitleDto(id, title)
        assertThat(texts(book.get("neverRead")))                           // trap 3: over-fetching
                .containsExactlyInAnyOrder("isbn", "publicationYear", "description", "author", "publisher");
        assertThat(book.get("perRequest").asDouble()).isEqualTo(5000.0);   // prolific author 7
    }

    @Test
    void entityModelIsExposed() throws Exception {
        JsonNode model = app.agentJson("/fq/model").get("entities");
        JsonNode book = find(model, "entity", DEMO + ".domain.Book");
        assertThat(find(book.get("attributes"), "name", "author").get("fetch").asText()).isEqualTo("LAZY");
        assertThat(texts(StreamSupport.stream(model.spliterator(), false).map(m -> m.get("entity")).collect(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance::arrayNode,
                com.fasterxml.jackson.databind.node.ArrayNode::add, (a, b) -> a.addAll(b))))
                .contains(DEMO + ".domain.Author", DEMO + ".domain.Publisher", DEMO + ".domain.Review");
    }

    static java.util.List<JsonNode> findings(String endpoint) throws Exception {
        return StreamSupport.stream(app.agentJson("/fq/findings").get("findings").spliterator(), false)
                .filter(f -> f.get("endpoint").asText().equals(endpoint)).toList();
    }

    static JsonNode finding(String endpoint, String kind) throws Exception {
        java.util.List<JsonNode> all = findings(endpoint);
        return all.stream().filter(f -> f.get("kind").asText().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " on " + endpoint + ": " + all));
    }

    @Test
    void everyTrapHasItsFindingAndTheWitnessHasNone() throws Exception {
        // trap 1: N+1 with its cause and caller
        JsonNode n1 = finding("GET /authors", "N_PLUS_ONE_LAZY");
        assertThat(n1.get("evidence").get("association").asText()).isEqualTo("Author.books");
        assertThat(n1.get("evidence").get("caller").asText()).startsWith(DEMO + ".web.AuthorController#toDto:");
        assertThat(n1.get("fix").asText()).contains("@EntityGraph(attributePaths = \"books\")");
        // trap 2: EAGER by default, never read
        JsonNode eager = finding("GET /books", "USELESS_EAGER");
        assertThat(eager.get("evidence").get("association").asText()).isEqualTo("Book.publisher");
        assertThat(eager.get("evidence").get("fetch").asText()).contains("JPA default");
        // trap 3: over-fetching, the large description never read
        JsonNode over = finding("GET /books/titles", "OVER_FETCHING");
        assertThat(texts(over.get("evidence").get("largeColumnsNeverRead"))).containsExactly("description");
        assertThat(texts(over.get("evidence").get("columnsRead"))).containsExactly("title");
        // trap 5: JOIN FETCH + pagination
        assertThat(finding("GET /authors/with-books", "IN_MEMORY_PAGINATION").get("evidence")
                .get("statement").asText()).contains("join book");
        // trap 6: open-in-view
        assertThat(finding("GET /authors", "OPEN_IN_VIEW").get("evidence").get("lazyLoadsOutsideTransaction")
                .get(0).get("caller").asText()).startsWith(DEMO + ".web.AuthorController#toDto:");
        // witness: DTO projection, nothing to say
        assertThat(findings("GET /publishers/{id}")).isEmpty();
    }

    @Test
    void witnessLoadsNoEntityAndNothingLazily() throws Exception {
        JsonNode p = profile("GET /publishers/{id}");      // DTO projection
        assertThat(p.get("entities")).isEmpty();
        assertThat(p.get("lazyLoads")).isEmpty();
        assertThat(p.get("inMemoryPaginationRequests").asLong()).isZero();
    }
}
