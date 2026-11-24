package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The acceptance rules on synthetic measurements (no Docker). */
class JudgeTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final JsonNode EAGER = json("""
            {"endpoint":"GET /books","kind":"USELESS_EAGER","title":"Book.publisher is loaded EAGERly",
             "evidence":{"association":"Book.publisher"}}""");
    static final JsonNode OVER = json("""
            {"endpoint":"GET /books","kind":"OVER_FETCHING","title":"50 Book loaded",
             "evidence":{"entity":"x.Book"}}""");
    static final JsonNode LAZY = json("""
            {"endpoint":"GET /authors","kind":"N_PLUS_ONE_LAZY","title":"N+1: Book.publisher",
             "evidence":{"association":"Book.publisher"}}""");
    static final String TARGET = BundleBuilder.findingId(EAGER);
    static final String BOOKS = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]";

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, JsonNode> findings(JsonNode... f) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode x : f) m.put(BundleBuilder.findingId(x), x);
        return m;
    }

    static Measurement m(String books, double booksStatements, double booksMs, double authorsStatements,
                         double authorsMs, Map<String, JsonNode> findings) {
        Map<String, Measurement.Response> r = new LinkedHashMap<>();
        r.put("/books?limit=50", new Measurement.Response(200, json(books)));
        r.put("/reviews/ranking", new Measurement.Response(200, null));
        Map<String, Measurement.EndpointCost> c = new LinkedHashMap<>();
        c.put("GET /books", new Measurement.EndpointCost(5, booksStatements, booksMs));
        c.put("GET /authors", new Measurement.EndpointCost(5, authorsStatements, authorsMs));
        return new Measurement(r, c, findings);
    }

    static final Measurement BEFORE = m(BOOKS, 45, 12.0, 22, 30.0, findings(EAGER, OVER));

    @Test
    void aFixThatRemovesTheFindingWithFewerStatementsAndTheSameJsonIsAccepted() {
        Verdict v = Judge.judge(TARGET, BEFORE, m(BOOKS, 1, 2.0, 22, 31.0, findings(OVER)), Judge.Thresholds.DEFAULT);

        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        assertThat(v.reasons()).isEmpty();
        assertThat(v.report().path("findingsResolved").get(0).path("kind").asText()).isEqualTo("USELESS_EAGER");
        assertThat(v.report().path("checks")).allMatch(c -> c.path("passed").asBoolean());
    }

    @Test
    void aDifferentJsonIsRejectedAndOnlyThePathIsReported() {
        String reordered = "[{\"id\":2,\"title\":\"B\"},{\"id\":1,\"title\":\"A\"}]";
        Verdict v = Judge.judge(TARGET, BEFORE, m(reordered, 1, 1.0, 22, 30.0, findings(OVER)), Judge.Thresholds.DEFAULT);

        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).containsExactly("response changed: /books?limit=50: JSON differs at /0/id");
    }

    @Test
    void fieldOrderDoesNotMatterButAMissingFieldDoes() {
        assertThat(Judge.firstDifference(json("{\"a\":1,\"b\":[1,2]}"), json("{\"b\":[1,2],\"a\":1}"), "")).isNull();
        assertThat(Judge.firstDifference(json("{\"a\":1,\"b\":2}"), json("{\"a\":1}"), "")).isEqualTo("/b");
        assertThat(Judge.firstDifference(json("[1,2]"), json("[1,2,3]"), "")).isEqualTo("/2");
        assertThat(Judge.firstDifference(json("{\"a\":1}"), json("{\"a\":1.0}"), "")).isEqualTo("/a");
        assertThat(Judge.firstDifference(json("{\"a\":null}"), json("{\"a\":\"x\"}"), "")).isEqualTo("/a");
    }

    @Test
    void aStatusChangeIsRejectedEvenWhereTheBodyIsNotCompared() {
        Measurement after = m(BOOKS, 1, 1.0, 22, 30.0, findings(OVER));
        after.responses().put("/reviews/ranking", new Measurement.Response(500, null));
        assertThat(Judge.judge(TARGET, BEFORE, after, Judge.Thresholds.DEFAULT).reasons())
                .containsExactly("response changed: /reviews/ranking: HTTP 200 before, 500 after");
    }

    @Test
    void aFixThatChangesNothingIsRejectedTwice() {
        Verdict v = Judge.judge(TARGET, BEFORE, m(BOOKS, 45, 11.8, 22, 30.0, findings(EAGER, OVER)), Judge.Thresholds.DEFAULT);

        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).hasSize(2);
        assertThat(v.reasons().get(0)).isEqualTo("the finding is still reported");
        assertThat(v.reasons().get(1)).startsWith("no measurable gain: GET /books: 45.00 -> 45.00 statements");
    }

    @Test
    void sameStatementsButMuchLessSqlTimeIsAGain() {
        // an index: same statements, the plan changes
        Verdict v = Judge.judge(TARGET, BEFORE, m(BOOKS, 45, 0.5, 22, 30.0, findings(OVER)), Judge.Thresholds.DEFAULT);
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        Verdict small = Judge.judge(TARGET, BEFORE, m(BOOKS, 45, 10.0, 22, 30.0, findings(OVER)), Judge.Thresholds.DEFAULT);
        assertThat(small.reasons()).singleElement().asString().startsWith("no measurable gain");
    }

    @Test
    void fewerStatementsButMoreSqlTimeIsNotAGain() {
        Verdict v = Judge.judge(TARGET, BEFORE, m(BOOKS, 2, 50.0, 22, 30.0, findings(OVER)), Judge.Thresholds.DEFAULT);
        assertThat(v.reasons()).singleElement().asString().startsWith("no measurable gain");
    }

    @Test
    void aNewFindingOrMoreStatementsElsewhereIsRejected() {
        Verdict v = Judge.judge(TARGET, BEFORE, m(BOOKS, 1, 1.0, 42, 60.0, findings(OVER, LAZY)), Judge.Thresholds.DEFAULT);

        assertThat(v.status()).isEqualTo(Verdict.Status.REJECTED);
        assertThat(v.reasons()).containsExactly(
                "new finding: N_PLUS_ONE_LAZY on GET /authors (N+1: Book.publisher)",
                "regression on GET /authors: 42.00 statements per request instead of 22.00");
        assertThat(v.report().path("findingsNew").get(0).path("kind").asText()).isEqualTo("N_PLUS_ONE_LAZY");
    }

    @Test
    void sqlTimeNoiseElsewhereIsToleratedButNotARealSlowdown() {
        assertThat(Judge.judge(TARGET, BEFORE, m(BOOKS, 1, 1.0, 22, 40.0, findings(OVER)), Judge.Thresholds.DEFAULT)
                .accepted()).isTrue();                                     // +33 %, under 30 % + 2 ms
        assertThat(Judge.judge(TARGET, BEFORE, m(BOOKS, 1, 1.0, 22, 45.0, findings(OVER)), Judge.Thresholds.DEFAULT)
                .reasons()).containsExactly("regression on GET /authors: 45.000 ms SQL per request instead of 30.000");
    }

    @Test
    void aFindingThatDoesNotShowUpWithoutTheFixIsInconclusive() {
        Verdict v = Judge.judge("useless-eager-00000000", BEFORE, BEFORE, Judge.Thresholds.DEFAULT);
        assertThat(v.status()).isEqualTo(Verdict.Status.INCONCLUSIVE);
    }

    @Test
    void costsAreThoseOfTheMeasuredRoundsOnly() {
        JsonNode warm = json("""
                {"profiles":[{"endpoint":"GET /books","profiledRequests":1,"queriesPerRequest":46.0,"sqlTimeMsPerRequest":80.0}]}""");
        JsonNode measured = json("""
                {"profiles":[{"endpoint":"GET /books","profiledRequests":6,"queriesPerRequest":45.17,"sqlTimeMsPerRequest":20.0},
                             {"endpoint":"GET /new","profiledRequests":5,"queriesPerRequest":1.0,"sqlTimeMsPerRequest":0.5}]}""");

        Map<String, Measurement.EndpointCost> c = Prover.costs(warm, measured);

        assertThat(c.get("GET /books").requests()).isEqualTo(5);
        assertThat(c.get("GET /books").statementsPerRequest()).isCloseTo(45.0, org.assertj.core.data.Offset.offset(0.05));
        assertThat(c.get("GET /books").sqlMsPerRequest()).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(c.get("GET /new").statementsPerRequest()).isEqualTo(1.0);
    }

    @Test
    void aSingleSlowRoundDoesNotDecide() {
        // cumulative snapshots: 1 request per round, SQL time 10, 10, 90 (a disk spill), 10 ms
        List<JsonNode> snapshots = new java.util.ArrayList<>();
        double[] perRound = {10, 10, 90, 10};
        double total = 0;
        snapshots.add(json("{\"profiles\":[]}"));
        for (int i = 0; i < perRound.length; i++) {
            total += perRound[i];
            snapshots.add(json("{\"profiles\":[{\"endpoint\":\"GET /r\",\"profiledRequests\":" + (i + 1)
                    + ",\"queriesPerRequest\":1.0,\"sqlTimeMsPerRequest\":" + total / (i + 1) + "}]}"));
        }
        Measurement.EndpointCost c = Prover.medianCosts(snapshots).get("GET /r");
        assertThat(c.requests()).isEqualTo(4);
        assertThat(c.sqlMsPerRequest()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(c.statementsPerRequest()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    static Measurement withRanking(Measurement m, double ms, String sql) {
        Map<String, Measurement.EndpointCost> c = new LinkedHashMap<>(m.endpoints());
        c.put("GET /reviews/ranking", new Measurement.EndpointCost(5, 1, ms, List.of("1.00 x " + sql)));
        return new Measurement(m.responses(), c, m.findings());
    }

    @Test
    void slowerWithTheSameSqlOnTheSameSchemaIsNoise() {
        String sql = "select r1_0.id from review r1_0 order by r1_0.created_at desc offset ? rows fetch first ? rows only";
        Measurement before = withRanking(BEFORE, 75.0, sql);
        Measurement after = withRanking(m(BOOKS, 1, 2.0, 22, 30.0, findings(OVER)), 107.0, sql);

        Verdict v = Judge.judge(TARGET, before, after, Judge.Thresholds.DEFAULT, false);
        assertThat(v.status()).isEqualTo(Verdict.Status.ACCEPTED);
        JsonNode ranking = java.util.stream.StreamSupport.stream(v.report().path("endpoints").spliterator(), false)
                .filter(e -> e.path("endpoint").asText().equals("GET /reviews/ranking")).findFirst().orElseThrow();
        assertThat(ranking.path("sameSql").asBoolean()).isTrue();
        assertThat(ranking.path("note").asText()).contains("measurement noise");

        // a migration can change the plan of the same SQL: then it counts
        assertThat(Judge.judge(TARGET, before, after, Judge.Thresholds.DEFAULT, true).reasons())
                .containsExactly("regression on GET /reviews/ranking: 107.000 ms SQL per request instead of 75.000");
        // ... unless every statement of the endpoint kept its plan (EXPLAIN on both databases)
        Verdict samePlan = Judge.judge(TARGET, before, after, Judge.Thresholds.DEFAULT, true, java.util.Set.of("GET /reviews/ranking"));
        assertThat(samePlan.status()).isEqualTo(Verdict.Status.ACCEPTED);
        assertThat(samePlan.report().path("endpoints").toString()).contains("the same SQL with the same plans: measurement noise");
        // other SQL: it counts
        assertThat(Judge.judge(TARGET, before, withRanking(after, 107.0, sql + " for update"), Judge.Thresholds.DEFAULT, false)
                .accepted()).isFalse();
        assertThat(Judge.judge(TARGET, before, withRanking(after, 107.0, sql + " for update"), Judge.Thresholds.DEFAULT, true,
                java.util.Set.of("GET /reviews/ranking")).accepted()).isFalse();
    }

    @Test
    void migrationsAreRecognised() {
        assertThat(Workspace.MIGRATION.matcher("demo-app/src/main/resources/db/changelog/002-index.sql").find()).isTrue();
        assertThat(Workspace.MIGRATION.matcher("app/src/main/resources/db/migration/V2__idx.sql").find()).isTrue();
        assertThat(Workspace.MIGRATION.matcher("app/src/main/resources/db/changelog/db.changelog-master.yaml").find()).isTrue();
        assertThat(Workspace.MIGRATION.matcher("demo-app/src/main/java/x/domain/Book.java").find()).isFalse();
    }
}
