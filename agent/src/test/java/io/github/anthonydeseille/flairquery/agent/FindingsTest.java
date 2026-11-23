package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.profile.Findings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules on hand-built profiles (the /fq/profile shape): one positive and one negative case per rule. */
class FindingsTest {

    static final String D = "com.x.domain.";

    static Map<String, Object> profile(String endpoint) {
        Map<String, Object> p = new HashMap<>();
        p.put("endpoint", endpoint);
        p.put("profiledRequests", 2L);
        p.put("rowsPerRequest", 10.0);
        p.put("entities", List.of());
        p.put("lazyLoads", List.of());
        p.put("statements", List.of());
        p.put("inMemoryPaginationRequests", 0L);
        p.put("openInViewRequests", 0L);
        return p;
    }

    static Map<String, Object> attr(String name, String kind, Object fetch, boolean large, double readRatio, String target) {
        Map<String, Object> a = new HashMap<>();
        a.put("name", name);
        a.put("kind", kind);
        if (fetch != null) {
            a.put("fetch", fetch);
            a.put("fetchExplicit", false);
        }
        if (large) a.put("large", true);
        if (target != null) a.put("target", target);
        a.put("readRatio", readRatio);
        return a;
    }

    static Map<String, Object> entity(String type, double perRequest, List<Map<String, Object>> attributes) {
        return Map.of("entity", D + type, "perRequest", perRequest, "attributes", attributes);
    }

    static List<String> kinds(List<Map<String, Object>> findings) {
        return findings.stream().map(f -> (String) f.get("kind")).toList();
    }

    @Test
    void lazyNPlusOneWithItsStatementsAndOpenInView() {
        Map<String, Object> p = profile("GET /authors");
        p.put("lazyLoads", List.of(Map.of("role", D + "Author.books", "kind", "collection", "perRequest", 20.0,
                "maxPerRequest", 20L, "outsideTransaction", 40L, "caller", "com.x.web.AuthorController#toDto:47")));
        p.put("statements", List.of(Map.of("sql", "select ... from book b1_0 where b1_0.author_id=?",
                "perRequest", 20.0, "timeMs", 12.0, "triggeredByLazyLoad", D + "Author.books")));
        p.put("openInViewRequests", 2L);

        List<Map<String, Object>> f = Findings.of(p, 5);

        assertThat(kinds(f)).containsExactly("N_PLUS_ONE_LAZY", "OPEN_IN_VIEW");     // most severe first
        assertThat(f.get(0)).containsEntry("severity", "HIGH");
        assertThat((String) f.get(0).get("title")).contains("Author.books", "20 times per request");
        assertThat((String) f.get(0).get("fix")).contains("@EntityGraph(attributePaths = \"books\")");
        @SuppressWarnings("unchecked")
        var ev = (Map<String, Object>) f.get(0).get("evidence");
        assertThat(ev.get("caller")).isEqualTo("com.x.web.AuthorController#toDto:47");
        assertThat((List<?>) ev.get("statements")).hasSize(1);
    }

    @Test
    void lazyLoadsUnderTheThresholdOrInsideATransactionAreFine() {
        Map<String, Object> p = profile("GET /a");
        p.put("lazyLoads", List.of(Map.of("role", D + "A.b", "kind", "collection", "perRequest", 3.0,
                "maxPerRequest", 3L, "outsideTransaction", 0L, "caller", "x")));
        p.put("openInViewRequests", 2L);                       // OSIV on, but nothing loaded through it
        assertThat(Findings.of(p, 5)).isEmpty();
    }

    @Test
    void eagerNeverReadWithItsTargetsLoadedIsUseless() {
        Map<String, Object> p = profile("GET /books");
        p.put("entities", List.of(
                entity("Book", 50, List.of(attr("id", "ID", null, false, 1, null), attr("title", "BASIC", null, false, 1, null),
                        attr("publisher", "TO_ONE", "EAGER", false, 0, D + "Publisher"))),
                entity("Publisher", 45, List.of(attr("id", "ID", null, false, 0, null),
                        attr("name", "BASIC", null, false, 0, null), attr("country", "BASIC", null, false, 0, null)))));

        List<Map<String, Object>> f = Findings.of(p, 5);

        // the 45 Publisher with none of their columns read are the EAGER's consequence, not a second finding
        assertThat(kinds(f)).containsExactly("USELESS_EAGER");
        assertThat((String) f.get(0).get("title")).contains("Book.publisher", "45 Publisher per request");
        assertThat((String) f.get(0).get("fix")).contains("@ManyToOne(fetch = FetchType.LAZY)");
        @SuppressWarnings("unchecked")
        var ev = (Map<String, Object>) f.get(0).get("evidence");
        assertThat((String) ev.get("fetch")).contains("JPA default");
    }

    @Test
    void eagerThatIsReadOrLazyNeverReadIsNotFlagged() {
        Map<String, Object> p = profile("GET /books");
        p.put("entities", List.of(
                entity("Book", 50, List.of(attr("title", "BASIC", null, false, 1, null),
                        attr("publisher", "TO_ONE", "EAGER", false, 0.5, D + "Publisher"),
                        attr("author", "TO_ONE", "LAZY", false, 0, D + "Author"))),
                entity("Publisher", 45, List.of())));
        assertThat(Findings.of(p, 5)).isEmpty();
    }

    @Test
    void overFetchingOnLargeOrMostlyUnreadColumns() {
        Map<String, Object> p = profile("GET /books/titles");
        p.put("entities", List.of(entity("Book", 5000, List.of(
                attr("id", "ID", null, false, 1, null), attr("title", "BASIC", null, false, 1, null),
                attr("isbn", "BASIC", null, false, 0, null), attr("year", "BASIC", null, false, 0, null),
                attr("description", "BASIC", null, true, 0, null)))));

        List<Map<String, Object>> f = Findings.of(p, 5);

        assertThat(kinds(f)).containsExactly("OVER_FETCHING");
        assertThat(f.get(0)).containsEntry("severity", "HIGH");
        assertThat((String) f.get(0).get("title")).contains("5000 Book", "1 of 4 columns read", "description");
        assertThat((String) f.get(0).get("fix")).contains("select new ...(e.title)");
    }

    @Test
    void fewEntitiesWithSmallUnreadColumnsAreNotOverFetching() {
        Map<String, Object> p = profile("GET /x");
        p.put("entities", List.of(entity("Book", 3, List.of(attr("title", "BASIC", null, false, 1, null),
                attr("isbn", "BASIC", null, false, 0, null), attr("year", "BASIC", null, false, 0, null)))));
        assertThat(Findings.of(p, 5)).isEmpty();
    }

    @Test
    void inMemoryPaginationPointsAtTheBiggestStatement() {
        Map<String, Object> p = profile("GET /authors/with-books");
        p.put("inMemoryPaginationRequests", 2L);
        p.put("rowsPerRequest", 21257.0);
        p.put("statements", List.of(Map.of("sql", "select count(...)", "rowsPerExecution", 1.0),
                Map.of("sql", "select distinct ... join book", "rowsPerExecution", 21257.0)));

        List<Map<String, Object>> f = Findings.of(p, 5);

        assertThat(kinds(f)).containsExactly("IN_MEMORY_PAGINATION");
        @SuppressWarnings("unchecked")
        var ev = (Map<String, Object>) f.get(0).get("evidence");
        assertThat(ev.get("statement")).isEqualTo("select distinct ... join book");
        assertThat((String) f.get(0).get("title")).contains("21257 rows read per request");
    }

    @Test
    void dtoProjectionWitnessIsClean() {
        assertThat(Findings.of(profile("GET /publishers/{id}"), 5)).isEmpty();
    }
}
