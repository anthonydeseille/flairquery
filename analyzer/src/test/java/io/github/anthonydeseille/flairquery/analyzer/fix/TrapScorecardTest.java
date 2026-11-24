package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.CLEAN;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.FIXED;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.FLAGGED;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.NOT_ANALYZED;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.NOT_FIXED;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.NOT_GENERATED;
import static io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard.Status.OUT_OF_SCOPE;
import static org.assertj.core.api.Assertions.assertThat;

class TrapScorecardTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static String finding(int trap) {
        return TrapScorecard.TRAPS.get(trap - 1).finding();
    }

    static JsonNode bundle(String id, String endpoint) {
        return JSON.createObjectNode().put("id", id).put("endpoint", endpoint);
    }

    /** An outcome for findings, its verdict resolving {@code resolved}, target endpoint 22 -> 2 statements. */
    static FixCommand.Outcome outcome(String name, String generator, int attempt, Verdict.Status status,
                                      List<String> findings, String... resolved) {
        List<JsonNode> bundles = new ArrayList<>(findings.stream().map(f -> bundle(f, "GET /authors")).toList());
        Fix fix = new Fix(generator, name, name, Map.of());
        FixCommand.Proposal p = new FixCommand.Proposal(fix, "", bundles, attempt, List.of(), null);
        ObjectNode report = JSON.createObjectNode();
        var r = report.putArray("findingsResolved");
        for (String id : resolved) r.addObject().put("id", id);
        ObjectNode e = report.putArray("endpoints").addObject().put("endpoint", "GET /authors").put("target", true);
        e.putObject("before").put("statementsPerRequest", 22.0).put("sqlMsPerRequest", 57.29);
        e.putObject("after").put("statementsPerRequest", 2.0).put("sqlMsPerRequest", 8.02);
        Verdict v = new Verdict(status, status == Verdict.Status.ACCEPTED ? List.of() : List.of("no measurable gain"), report);
        return new FixCommand.Outcome(p, Path.of(name + ".patch"), v);
    }

    static Map<Integer, TrapScorecard.Row> byTrap(List<TrapScorecard.Row> rows) {
        return rows.stream().filter(r -> r.trap() != null).collect(Collectors.toMap(TrapScorecard.Row::trap, r -> r));
    }

    @Test
    void aTrapIsFixedOnlyByAnAcceptedFixResolvingItsFinding() {
        List<FixCommand.Outcome> outcomes = List.of(
                // trap 1: rejected, then accepted at attempt 2; it also resolves trap 6
                outcome("llm-a", "llm", 1, Verdict.Status.REJECTED, List.of(finding(1))),
                outcome("llm-b", "llm", 2, Verdict.Status.ACCEPTED, List.of(finding(1)), finding(1), finding(6)),
                // trap 6: its own fix rejected, but resolved by trap 1's
                outcome("llm-c", "llm", 1, Verdict.Status.REJECTED, List.of(finding(6))),
                // trap 3: rejected only
                outcome("llm-d", "llm", 1, Verdict.Status.REJECTED, List.of(finding(3))),
                // trap 2: accepted but did not resolve its finding (only another one)
                outcome("lazy", "lazy-association", 1, Verdict.Status.ACCEPTED, List.of(finding(2)), "other"));
        Set<String> analyzed = Set.of(finding(1), finding(2), finding(3), finding(5), finding(6));
        Map<Integer, TrapScorecard.Row> rows = byTrap(TrapScorecard.of(outcomes, analyzed));

        assertThat(rows.get(1)).satisfies(r -> {
            assertThat(r.status()).isEqualTo(FIXED);
            assertThat(r.fix()).isEqualTo("llm-b");
            assertThat(r.attempt()).isEqualTo(2);
            assertThat(r.detail()).isEqualTo("GET /authors: 22 -> 2 statements, 57.3 -> 8.0 ms SQL per request");
        });
        assertThat(rows.get(6).status()).isEqualTo(FIXED);
        assertThat(rows.get(6).fix()).isEqualTo("llm-b");
        assertThat(rows.get(3).status()).isEqualTo(NOT_FIXED);
        assertThat(rows.get(3).detail()).isEqualTo("REJECTED: no measurable gain");
        assertThat(rows.get(2).status()).isEqualTo(NOT_FIXED);
        assertThat(rows.get(4).status()).isEqualTo(NOT_ANALYZED);
        assertThat(rows.get(5).status()).isEqualTo(NOT_GENERATED);
        assertThat(rows.get(7).status()).isEqualTo(OUT_OF_SCOPE);

        List<TrapScorecard.Row> all = TrapScorecard.of(outcomes, analyzed);
        assertThat(all.get(all.size() - 1).status()).isEqualTo(CLEAN);
        assertThat(TrapScorecard.summary(all)).isEqualTo("2/6 traps fixed and proven");
        assertThat(TrapScorecard.markdown(all)).contains("| 1 | N+1 authors -> books | FIXED | `llm-b` | llm | 2 | GET /authors: 22 -> 2");
        assertThat(TrapScorecard.text(all)).contains("llm-b [llm, attempt 2]");
    }

    @Test
    void aFixTargetingTheWitnessFlagsIt() {
        FixCommand.Outcome o = outcome("x", "llm", 1, Verdict.Status.REJECTED, List.of(finding(1)));
        o.proposal().bundles().add(bundle("w", "GET /publishers/{id}"));
        List<TrapScorecard.Row> rows = TrapScorecard.of(List.of(o), Set.of(finding(1)));
        assertThat(rows.get(rows.size() - 1).status()).isEqualTo(FLAGGED);
    }

    @Test
    void theDemoIsRecognizedByItsTrapFindings() throws Exception {
        assertThat(TrapScorecard.isDemo(Set.of(finding(4)))).isTrue();
        assertThat(TrapScorecard.isDemo(Set.of("n-plus-one-lazy-00000000"))).isFalse();
        // every trap in scope is among the findings of the recorded demo bundles
        GeneratorsTest.bundles();
        assertThat(GeneratorsTest.bundles.stream().map(b -> b.path("id").asText()).toList())
                .containsAll(TrapScorecard.TRAPS.stream().map(TrapScorecard.Trap::finding)
                        .filter(java.util.Objects::nonNull).toList());
    }
}
