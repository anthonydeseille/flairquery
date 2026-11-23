package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.compare.Comparison;
import io.github.anthonydeseille.flairquery.collector.compare.Periods;
import io.github.anthonydeseille.flairquery.collector.store.ColumnStatsMath;
import io.github.anthonydeseille.flairquery.collector.store.Store.EndpointUse;
import io.github.anthonydeseille.flairquery.collector.store.Store.Mark;
import io.github.anthonydeseille.flairquery.collector.store.Store.Period;
import io.github.anthonydeseille.flairquery.collector.store.Store.PeriodData;
import io.github.anthonydeseille.flairquery.collector.store.Store.PlanUse;
import io.github.anthonydeseille.flairquery.collector.store.Store.StatementId;
import io.github.anthonydeseille.flairquery.collector.store.Store.StatementTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ComparisonTest {

    static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
    static final StatementId SEARCH = new StatementId("flairquery", "flairquery", 11);
    static final StatementId AUTHOR_BOOKS = new StatementId("flairquery", "flairquery", 22);
    static final StatementId AUTHOR_FETCH = new StatementId("flairquery", "flairquery", 33);

    static StatementTotals totals(long statementId, StatementId id, long calls, double ms, long read) {
        StatementTotals t = new StatementTotals();
        t.statementId = statementId;
        t.id = id;
        t.sql = "q" + id.queryid();
        t.calls = calls;
        t.totalMs = ms;
        t.sharedBlksRead = read;
        return t;
    }

    static PeriodData data(String label, List<StatementTotals> st, List<PlanUse> plans, List<EndpointUse> eps) {
        Map<Long, StatementTotals> m = new LinkedHashMap<>();
        st.forEach(t -> m.put(t.statementId, t));
        return new PeriodData(new Period(label, T0, T0.plusSeconds(60)), 3, false, m, plans, eps);
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexFixAndNPlusOneFixAreVisible() {
        PeriodData before = data("before", List.of(
                        totals(1, SEARCH, 10, 5000, 80_000),
                        totals(2, AUTHOR_BOOKS, 1000, 900, 10)),
                List.of(new PlanUse(new StatementId("flairquery", null, 11), "seq", "{\"seqScans\":[{\"relation\":\"review\"}]}", 10)),
                List.of(new EndpointUse(SEARCH, "demo", "GET /reviews/search", 10, 5000),
                        new EndpointUse(AUTHOR_BOOKS, "demo", "GET /authors", 1000, 900)));
        PeriodData after = data("after", List.of(
                        totals(1, SEARCH, 10, 50, 40),
                        totals(3, AUTHOR_FETCH, 1, 30, 5)),
                List.of(new PlanUse(new StatementId("flairquery", null, 11), "idx", "{\"seqScans\":[]}", 10)),
                List.of(new EndpointUse(SEARCH, "demo", "GET /reviews/search", 10, 50),
                        new EndpointUse(AUTHOR_FETCH, "demo", "GET /authors", 1, 30)));

        Map<String, Object> c = Comparison.of(before, after, 20);

        List<Map<String, Object>> statements = (List<Map<String, Object>>) c.get("statements");
        Map<String, Object> search = statements.get(0);    // largest absolute time change first
        assertThat(search).containsEntry("queryid", "11").containsEntry("status", "both")
                .containsEntry("endpoints", List.of("GET /reviews/search"));
        assertThat(((Map<String, Object>) search.get("changePct")).get("totalTimeMs")).isEqualTo(-99.0);
        Map<String, Object> plans = (Map<String, Object>) search.get("plans");
        assertThat(plans.get("changed")).isEqualTo(true);

        assertThat(statements).extracting(s -> s.get("queryid") + ":" + s.get("status"))
                .containsExactlyInAnyOrder("11:both", "22:gone", "33:new");
        Map<String, Object> gone = statements.stream().filter(s -> s.get("queryid").equals("22")).findFirst().orElseThrow();
        assertThat(gone.get("after")).isNull();
        assertThat(((Map<String, Object>) gone.get("changePct")).get("calls")).isEqualTo(-100.0);

        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) c.get("endpoints");
        Map<String, Object> authors = endpoints.stream().filter(e -> e.get("endpoint").equals("GET /authors"))
                .findFirst().orElseThrow();
        assertThat((Map<String, Object>) authors.get("before")).containsEntry("executions", 1000L);
        assertThat((Map<String, Object>) authors.get("after")).containsEntry("executions", 1L);

        Map<String, Object> total = (Map<String, Object>) c.get("totals");
        assertThat((Map<String, Object>) total.get("before")).containsEntry("calls", 1010L);
        assertThat((Map<String, Object>) total.get("after")).containsEntry("calls", 11L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitAndEmptyPeriodNote() {
        PeriodData before = data("before", List.of(totals(1, SEARCH, 1, 1, 0), totals(2, AUTHOR_BOOKS, 1, 2, 0)),
                List.of(), List.of());
        PeriodData empty = new PeriodData(new Period("after", T0, T0.plusSeconds(1)), 0, false, Map.of(), List.of(), List.of());

        Map<String, Object> c = Comparison.of(before, empty, 1);

        assertThat((List<Object>) c.get("statements")).hasSize(1);
        assertThat((List<String>) c.get("notes")).anyMatch(n -> n.contains("no collection cycle"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withAgentRequestCountsValuesArePerRequest() {
        // twice the traffic after, same cost per request for /authors, half the statements per request for /books
        PeriodData before = new PeriodData(new Period("before", T0, T0.plusSeconds(60)), 3, false,
                Map.of(2L, totals(2, AUTHOR_BOOKS, 200, 100, 0)), List.of(),
                List.of(new EndpointUse(AUTHOR_BOOKS, "demo", "GET /authors", 200, 100)),
                Map.of("GET /authors", new long[]{10, 220, 100_000, 300_000}, "GET /books", new long[]{10, 450, 50_000, 90_000}));
        PeriodData after = new PeriodData(new Period("after", T0, T0.plusSeconds(60)), 3, false,
                Map.of(2L, totals(2, AUTHOR_BOOKS, 400, 200, 0)), List.of(),
                List.of(new EndpointUse(AUTHOR_BOOKS, "demo", "GET /authors", 400, 200)),
                Map.of("GET /authors", new long[]{20, 440, 200_000, 600_000}, "GET /books", new long[]{20, 450, 40_000, 80_000}));

        Map<String, Object> c = Comparison.of(before, after, 20);

        assertThat((List<String>) c.get("notes")).anyMatch(n -> n.contains("Per-request values") && n.contains("20 requests before, 40 after"));
        Map<String, Object> totals = (Map<String, Object>) c.get("totals");
        assertThat(((Map<String, Object>) totals.get("before")).get("httpRequests")).isEqualTo(20L);

        Map<String, Object> books = ((List<Map<String, Object>>) c.get("endpoints")).stream()
                .filter(e -> e.get("endpoint").equals("GET /books")).findFirst().orElseThrow();
        assertThat((Map<String, Object>) books.get("before")).containsEntry("statementsPerRequest", 45L);
        assertThat((Map<String, Object>) books.get("after")).containsEntry("statementsPerRequest", 22.5);
        assertThat(((Map<String, Object>) books.get("changePct")).get("statementsPerRequest")).isEqualTo(-50.0);

        Map<String, Object> authors = ((List<Map<String, Object>>) c.get("endpoints")).stream()
                .filter(e -> e.get("endpoint").equals("GET /authors")).findFirst().orElseThrow();
        assertThat(((Map<String, Object>) authors.get("changePct")).get("sqlTimeMsPerRequest")).isEqualTo(0.0);
        assertThat(((Map<String, Object>) authors.get("changePct")).get("executions")).isEqualTo(100.0);   // total doubled

        Map<String, Object> stmt = ((List<Map<String, Object>>) c.get("statements")).get(0);
        assertThat((Map<String, Object>) stmt.get("before")).containsEntry("callsPerRequest", 20L);
        assertThat(((Map<String, Object>) stmt.get("changePct")).get("callsPerRequest")).isEqualTo(0.0);
    }

    @Test
    void agentEndpointsAreParsed() throws Exception {
        var rows = io.github.anthonydeseille.flairquery.collector.source.AgentSource.parse("""
                {"schemaVersion":1,"app":"demo-app","endpoints":[
                 {"endpoint":"GET /authors","requests":12,"queries":264,"sqlTimeMs":80.5,"httpTimeMs":200.25}]}""");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.app()).isEqualTo("demo-app");
            assertThat(r.endpoint()).isEqualTo("GET /authors");
            assertThat(r.counters().get(0)).isEqualTo(12);
            assertThat(r.counters().get(3)).isEqualTo(200.25);
        });
    }

    @Test
    void periodsFromMarksAndInstants() {
        List<Mark> marks = List.of(new Mark("before", null, T0, 1L), new Mark("fix", null, T0.plusSeconds(100), 2L));
        Instant now = T0.plusSeconds(500);

        assertThat(Periods.resolve("before", "before", marks, now))
                .isEqualTo(new Period("before", T0, T0.plusSeconds(100)));    // up to the next mark
        assertThat(Periods.resolve("fix", "after", marks, now).end()).isEqualTo(now);
        assertThat(Periods.resolve("2026-11-23T09:00:00Z..before", "before", marks, now).start())
                .isEqualTo(Instant.parse("2026-11-23T09:00:00Z"));
        assertThat(Periods.resolve("before..now", "x", marks, now).end()).isEqualTo(now);

        assertThatThrownBy(() -> Periods.resolve("nope", "before", marks, now)).hasMessageContaining("unknown mark");
        assertThatThrownBy(() -> Periods.resolve("fix..before", "after", marks, now)).hasMessageContaining("after the start");
        assertThatThrownBy(() -> Periods.resolve(null, "after", marks, now)).hasMessageContaining("required");
        assertThat(Periods.MARK_NAME.matcher("before-fix_2.1").matches()).isTrue();
        assertThat(Periods.MARK_NAME.matcher("a b").matches()).isFalse();
    }

    @Test
    void columnStatsMath() {
        // 3M rows, 100 000 distinct reviewers, no nulls: an equality matches ~30 rows -> selective
        assertThat(ColumnStatsMath.rowsPerValue(100_000.0, 0.0, 3_000_000.0)).isCloseTo(30.0, within(0.001));
        // n_distinct = -1: unique column
        assertThat(ColumnStatsMath.distinct(-1.0, 5000.0)).isEqualTo(5000.0);
        assertThat(ColumnStatsMath.rowsPerValue(-1.0, 0.0, 5000.0)).isEqualTo(1.0);
        // half nulls
        assertThat(ColumnStatsMath.rowsPerValue(10.0, 0.5, 1000.0)).isEqualTo(50.0);
        // never analyzed
        assertThat(ColumnStatsMath.distinct(-0.5, -1.0)).isNull();
        assertThat(ColumnStatsMath.rowsPerValue(null, null, 10.0)).isNull();
    }
}
