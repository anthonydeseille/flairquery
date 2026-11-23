package io.github.anthonydeseille.flairquery.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.collector.plan.Attribution;
import io.github.anthonydeseille.flairquery.collector.plan.PlanAnalyzer;
import io.github.anthonydeseille.flairquery.collector.source.LogEntry;
import io.github.anthonydeseille.flairquery.collector.source.SqlLogPlanSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoExplainParsingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Shape of a PostgreSQL 16 jsonlog line written by auto_explain (log_format = json). */
    static String line(String user, long queryId, String tag, double costs) throws Exception {
        String plan = """
                {
                  "Query Text": "%sselect r1_0.id from review r1_0 where r1_0.reviewer_name=$1 order by r1_0.id fetch first $2 rows only",
                  "Plan": {
                    "Node Type": "Limit", "Startup Cost": %s, "Total Cost": %s, "Plan Rows": 20,
                    "Plans": [ { "Node Type": "Sort", "Parent Relationship": "Outer", "Sort Key": ["r1_0.id"],
                      "Plans": [ { "Node Type": "Seq Scan", "Parent Relationship": "Outer", "Relation Name": "review",
                                   "Alias": "r1_0", "Total Cost": %s, "Plan Rows": 30,
                                   "Filter": "((reviewer_name)::text = 'Alice Martin 00000'::text)" } ] } ]
                  }
                }""".formatted(tag, costs, costs + 1, costs + 2);
        var n = JSON.createObjectNode();
        n.put("timestamp", "2026-11-23 10:36:54.361 UTC");
        n.put("user", user);
        n.put("dbname", "flairquery");
        n.put("error_severity", "LOG");
        n.put("message", "duration: 1578.827 ms  plan:\n" + plan);
        n.put("backend_type", "client backend");
        n.put("query_id", queryId);
        return JSON.writeValueAsString(n);
    }

    @Test
    void parsesAnAutoExplainEntry() throws Exception {
        LogEntry e = parse(line("flairquery", -42L, "/* fq:app=demo,endpoint=GET /reviews/search,caller=c.R#search:32 */ ", 100));

        assertThat(e.queryId()).isEqualTo(-42L);
        assertThat(e.roleName()).isEqualTo("flairquery");
        assertThat(e.durationMs()).isEqualTo(1578.827);
        assertThat(e.queryText()).startsWith("/* fq:app=demo,endpoint=GET /reviews/search");
        assertThat(e.plan().path("Node Type").asText()).isEqualTo("Limit");
    }

    @Test
    void ignoresNonAutoExplainLines() throws Exception {
        assertThat(parse("{\"timestamp\":\"2026-11-23 10:00:00.000 UTC\",\"message\":\"database system is ready\"}")).isNull();
        assertThat(parse("{\"message\":\"duration: 1 ms  statement: select 1\"}")).isNull();
    }

    @Test
    void summaryFindsSeqScanWithFilterSortAndLimit() throws Exception {
        JsonNode plan = parse(line("u", 1, "", 10)).plan();
        JsonNode s = PlanAnalyzer.summary(plan);

        assertThat(s.path("seqScans").get(0).path("relation").asText()).isEqualTo("review");
        assertThat(s.path("seqScans").get(0).path("filter").asText()).contains("reviewer_name");
        assertThat(s.path("sorts").get(0).path("keys").get(0).asText()).isEqualTo("r1_0.id");
        assertThat(s.path("hasLimit").asBoolean()).isTrue();
    }

    @Test
    void planValuesAreMaskedBeforeAnythingElse() throws Exception {
        JsonNode plan = parse(line("u", 1, "", 10)).plan();

        assertThat(plan.toString()).doesNotContain("Alice", "00000")
                .contains("reviewer_name")
                .contains("\"Relation Name\":\"review\"")          // structure kept
                .contains("r1_0.id");                                 // identifiers with digits kept
        assertThat(PlanAnalyzer.summary(plan).path("seqScans").get(0).path("filter").asText())
                .isEqualTo("((reviewer_name)::text = ?::text)");

        JsonNode numbers = io.github.anthonydeseille.flairquery.collector.plan.PlanMasker.mask(JSON.readTree("""
                {"Node Type":"Index Scan","Index Name":"idx_2024","Index Cond":"(id = 42)",
                 "Filter":"((rating >= 4) AND (created_at > '2026-01-01 00:00:00+00'::timestamp with time zone))",
                 "Plans":[{"Node Type":"Seq Scan","Relation Name":"t1","Filter":"(name = ANY ('{bob,alice}'::text[]))"}]}"""));
        assertThat(numbers.toString()).doesNotContain("42", "2026", "bob", "alice", ">= 4")
                .contains("idx_2024", "\"t1\"");
    }

    @Test
    void indexScansUsedOnlyForTheirOrderAreReportedWithTheirFilter() throws Exception {
        JsonNode plan = JSON.readTree("""
                {"Node Type":"Limit","Plans":[{"Node Type":"Gather Merge","Plans":[
                  {"Node Type":"Index Scan","Relation Name":"review","Index Name":"review_pkey",
                   "Filter":"((reviewer_name)::text = ?::text)"}]}]}""");
        JsonNode s = PlanAnalyzer.summary(plan);

        assertThat(s.path("seqScans")).isEmpty();
        assertThat(s.path("filteredScans").get(0).path("node").asText()).isEqualTo("Index Scan");
        assertThat(s.path("filteredScans").get(0).path("index").asText()).isEqualTo("review_pkey");
        assertThat(s.path("filteredScans").get(0).path("filter").asText()).contains("reviewer_name");
    }

    @Test
    void fingerprintIgnoresCostsButNotShape() throws Exception {
        JsonNode a = parse(line("u", 1, "", 10)).plan();
        JsonNode b = parse(line("u", 1, "", 99999)).plan();
        JsonNode other = JSON.readTree("{\"Node Type\":\"Index Scan\",\"Relation Name\":\"review\",\"Index Name\":\"idx_x\"}");

        assertThat(PlanAnalyzer.fingerprint(a)).isEqualTo(PlanAnalyzer.fingerprint(b));
        assertThat(PlanAnalyzer.fingerprint(a)).isNotEqualTo(PlanAnalyzer.fingerprint(other));
    }

    @Test
    void attributionGroupsByTagAndExcludesTheCollectorRole() throws Exception {
        List<LogEntry> entries = List.of(
                parse(line("app", 7, "/* fq:app=demo,endpoint=GET /a,caller=c.A#a:1 */ ", 1)),
                parse(line("app", 7, "/* fq:app=demo,endpoint=GET /a,caller=c.A#a:1 */ ", 1)),
                parse(line("app", 7, "/* fq:app=demo,endpoint=GET /b,caller=c.B#b:2 */ ", 1)),
                parse(line("fq_reader", 7, "", 1)));

        Attribution.Result r = Attribution.of(entries, "fq_reader");

        assertThat(r.executions()).hasSize(2);
        assertThat(r.executions().get(new Attribution.Key("flairquery", "app", 7, "demo", "GET /a", "c.A#a:1")).count).isEqualTo(2);
        assertThat(r.executions().get(new Attribution.Key("flairquery", "app", 7, "demo", "GET /b", "c.B#b:2")).count).isEqualTo(1);
        assertThat(r.plans()).hasSize(1);
        assertThat(r.plans().values().iterator().next().count).isEqualTo(3);
    }

    @Test
    void incrementalReadHelpers() {
        assertThat(SqlLogPlanSource.class).isNotNull();
        byte[] partial = "{\"a\":1}\n{\"b\":2}\n{\"c\":".getBytes(StandardCharsets.UTF_8);
        assertThat(lastNewline(partial)).isEqualTo(15);
        assertThat(sameContent("{\"timestamp\":\"2026", "{\"timestamp\":\"2026-11-23 10")).isTrue(); // file grew
        assertThat(sameContent("{\"timestamp\":\"2026-11-22 10", "{\"timestamp\":\"2026-11-23 10")).isFalse(); // reused
        assertThat(sameContent(null, "x")).isFalse();
    }

    // package-private helpers of SqlLogPlanSource, reached through reflection-free wrappers
    private static LogEntry parse(String line) throws Exception {
        return (LogEntry) invoke("parse", new Class<?>[]{String.class}, line);
    }

    private static int lastNewline(byte[] b) {
        return (int) invoke("lastNewline", new Class<?>[]{byte[].class}, (Object) b);
    }

    private static boolean sameContent(String a, String b) {
        return (boolean) invoke("sameContent", new Class<?>[]{String.class, String.class}, a, b);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            var m = SqlLogPlanSource.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
