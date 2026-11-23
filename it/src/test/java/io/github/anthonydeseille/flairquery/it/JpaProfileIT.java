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

    @Test
    void witnessLoadsNoEntityAndNothingLazily() throws Exception {
        JsonNode p = profile("GET /publishers/{id}");      // DTO projection
        assertThat(p.get("entities")).isEmpty();
        assertThat(p.get("lazyLoads")).isEmpty();
        assertThat(p.get("inMemoryPaginationRequests").asLong()).isZero();
    }
}
