package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.CLEAN;
import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.DEFERRED;
import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.FLAGGED;
import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.NOT_PROPOSED;
import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.OUT_OF_SCOPE;
import static io.github.anthonydeseille.flairquery.analyzer.mr.MrScorecard.Status.PROPOSED;
import static org.assertj.core.api.Assertions.assertThat;

class MrScorecardTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static String finding(int trap) {
        return TrapScorecard.TRAPS.get(trap - 1).finding();
    }

    static FixEntry fix(String name, String generator, List<String> findings, String... resolves) {
        return new FixEntry(name, generator, name, findings, 1, null, "ACCEPTED", true, List.of(resolves), null);
    }

    static ObjectNode decision(ArrayNode index, String fix, Selection.Decision d, String why) {
        ObjectNode e = index.addObject().put("fix", fix).put("decision", d.name()).put("why", why);
        if (d == Selection.Decision.SELECTED) e.put("branch", "flairquery/" + fix);
        return e;
    }

    static Map<Integer, MrScorecard.Row> byTrap(List<MrScorecard.Row> rows) {
        return rows.stream().filter(r -> r.trap() != null).collect(Collectors.toMap(MrScorecard.Row::trap, r -> r));
    }

    /** The demo's fixes and decisions (FixIT): 3 merge requests, trap 6's fix redundant, traps 3 and 5 deferred. */
    static List<FixEntry> fixes = List.of(
            fix("lazy-book-publisher", "lazy-association", List.of(finding(2), "useless-eager-952de888"),
                    finding(2), "useless-eager-952de888"),
            fix("index-review-reviewer-name", "index-migration", List.of(finding(4)), finding(4)),
            fix("llm-trap1", "llm", List.of(finding(1)), finding(1), finding(6), "useless-eager-952de888"),
            fix("llm-trap3", "llm", List.of(finding(3)), finding(3)),
            fix("llm-trap5", "llm", List.of(finding(5)), finding(5)),
            fix("llm-trap6", "llm", List.of(finding(6)), finding(1), finding(6), "useless-eager-952de888"));

    static ArrayNode demoIndex() {
        ArrayNode index = JSON.createArrayNode();
        decision(index, "lazy-book-publisher", Selection.Decision.SELECTED, "resolves 2 finding(s)")
                .put("test", "src/test/java/demo/BookPublisherLazyRegressionTest.java").put("red", "ran 6, expected at most 2");
        decision(index, "index-review-reviewer-name", Selection.Decision.SELECTED, "resolves 1 finding(s)")
                .put("noTest", "no test proven red / green in 3 attempt(s)");
        decision(index, "llm-trap1", Selection.Decision.SELECTED, "resolves 3 finding(s)")
                .put("test", "src/test/java/demo/AuthorsListQueriesRegressionTest.java").put("red", "N+1 is back: 5 statements");
        decision(index, "llm-trap6", Selection.Decision.REDUNDANT, "every finding it resolves is resolved by llm-trap1");
        decision(index, "llm-trap5", Selection.Decision.DEFERRED, "changes the same lines as llm-trap1 (AuthorRepository.java)");
        decision(index, "llm-trap3", Selection.Decision.DEFERRED, "changes the same lines as llm-trap1 (BookRepository.java)");
        return index;
    }

    @Test
    void aTrapIsProposedWhenASelectedFixResolvesIt() {
        List<MrScorecard.Row> all = MrScorecard.of(fixes, demoIndex(), Map.of(finding(1), JSON.createObjectNode()
                .put("endpoint", "GET /authors")));
        Map<Integer, MrScorecard.Row> rows = byTrap(all);

        assertThat(rows.get(1)).satisfies(r -> {
            assertThat(r.status()).isEqualTo(PROPOSED);
            assertThat(r.branch()).isEqualTo("flairquery/llm-trap1");
            assertThat(r.detail()).isEqualTo("its own fix");
            assertThat(r.test()).isEqualTo("red -> green (AuthorsListQueriesRegressionTest): N+1 is back: 5 statements");
            assertThat(r.testProven()).isTrue();
        });
        // trap 6: covered by trap 1's merge request, its own fix redundant
        assertThat(rows.get(6)).satisfies(r -> {
            assertThat(r.status()).isEqualTo(PROPOSED);
            assertThat(r.fix()).isEqualTo("llm-trap1");
            assertThat(r.detail()).isEqualTo("resolved by this merge request too; its own fix llm-trap6 REDUNDANT");
        });
        assertThat(rows.get(2).fix()).isEqualTo("lazy-book-publisher");
        assertThat(rows.get(4)).satisfies(r -> {
            assertThat(r.status()).isEqualTo(PROPOSED);
            assertThat(r.testProven()).isFalse();
            assertThat(r.test()).startsWith("no test: no test proven");
        });
        assertThat(rows.get(3).status()).isEqualTo(DEFERRED);
        assertThat(rows.get(3).detail()).contains("BookRepository.java");
        assertThat(rows.get(5).status()).isEqualTo(DEFERRED);
        assertThat(rows.get(7).status()).isEqualTo(OUT_OF_SCOPE);
        assertThat(all.get(all.size() - 1).status()).isEqualTo(CLEAN);

        assertThat(MrScorecard.summary(all)).isEqualTo(
                "4/6 traps proposed as merge requests (3 merge requests, 2 with a regression test proven red then green), 2 deferred");
        assertThat(MrScorecard.markdown(all)).contains("| 6 | open-in-view | PROPOSED | `flairquery/llm-trap1` |");
        assertThat(MrScorecard.text(all)).contains("flairquery/llm-trap1 (its own fix) test red -> green");
    }

    @Test
    void aTrapWhoseFixIsNotProvenOrNotGeneratedIsNotProposed() {
        List<FixEntry> some = List.of(new FixEntry("llm-trap3", "llm", "", List.of(finding(3)), 3, null, "REJECTED", false,
                List.of(), null));
        ArrayNode index = JSON.createArrayNode();
        decision(index, "llm-trap3", Selection.Decision.NOT_PROVEN, "REJECTED");
        Map<Integer, MrScorecard.Row> rows = byTrap(MrScorecard.of(some, index, Map.of()));
        assertThat(rows.get(3).status()).isEqualTo(NOT_PROPOSED);
        assertThat(rows.get(3).detail()).isEqualTo("llm-trap3 NOT_PROVEN: REJECTED");
        assertThat(rows.get(1).detail()).isEqualTo("no fix generated for its finding");
        // no bundles: no witness row
        assertThat(MrScorecard.of(some, index, Map.of())).allMatch(r -> r.trap() != null);
    }

    @Test
    void aMergeRequestTargetingTheWitnessFlagsIt() {
        JsonNode witness = JSON.createObjectNode().put("endpoint", TrapScorecard.WITNESS);
        List<FixEntry> some = List.of(fix("w", "llm", List.of("w-1"), "w-1"));
        ArrayNode index = JSON.createArrayNode();
        decision(index, "w", Selection.Decision.SELECTED, "");
        List<MrScorecard.Row> rows = MrScorecard.of(some, index, Map.of("w-1", witness));
        assertThat(rows.get(rows.size() - 1).status()).isEqualTo(FLAGGED);
        assertThat(rows.get(rows.size() - 1).detail()).isEqualTo("targeted by w");
    }
}
