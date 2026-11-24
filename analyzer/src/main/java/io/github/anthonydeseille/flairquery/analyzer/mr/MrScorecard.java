package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.fix.TrapScorecard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5 scorecard of the demo app's traps (ground truth: {@code demo-app/TRAPS.md}): for each trap, whether it
 * is proposed as a merge request, by which one and with which regression test. A trap is PROPOSED when a SELECTED
 * fix resolves its finding (its own fix, or another one that also resolves it: its own fix is then redundant);
 * DEFERRED when only a deferred fix resolves it (same lines changed as a selected one: proven again once that merge
 * request is merged); NOT_PROPOSED otherwise. The witness stays CLEAN when no merge request targets it.
 */
public final class MrScorecard {

    public enum Status { PROPOSED, DEFERRED, NOT_PROPOSED, OUT_OF_SCOPE, CLEAN, FLAGGED }

    /**
     * @param fix    the fix of the merge request (or the deferred fix), null if none
     * @param branch its branch, null when not proposed
     * @param test   the regression test: "red -> green" with its red message, or why there is none (null when not
     *               proposed)
     * @param detail what covers the trap, or why it is not proposed
     */
    public record Row(Integer trap, String title, Status status, String fix, String branch, String test, String detail) {
        public boolean testProven() {
            return test != null && test.startsWith("red -> green");
        }
    }

    private MrScorecard() { }

    /**
     * @param fixes   {@code fix}'s index (findings and the findings each proof resolved)
     * @param mrs     {@code mr}'s index ({@code target/mr/index.json}): one decision per fix
     * @param bundles the bundles by finding id (endpoint of each finding, for the witness); empty: no witness row
     */
    public static List<Row> of(List<FixEntry> fixes, JsonNode mrs, Map<String, JsonNode> bundles) {
        Map<String, FixEntry> byName = new HashMap<>();
        fixes.forEach(f -> byName.put(f.name(), f));
        List<JsonNode> decisions = new ArrayList<>();
        mrs.forEach(decisions::add);
        Set<String> analyzed = new LinkedHashSet<>();
        fixes.forEach(f -> analyzed.addAll(f.findings()));

        List<Row> rows = new ArrayList<>();
        for (TrapScorecard.Trap t : TrapScorecard.TRAPS) {
            String finding = t.finding();
            if (finding == null) {
                rows.add(new Row(t.number(), t.title(), Status.OUT_OF_SCOPE, null, null, null,
                        "keyset pagination changes the API; work_mem is phase 6"));
                continue;
            }
            if (!analyzed.contains(finding)) {
                rows.add(new Row(t.number(), t.title(), Status.NOT_PROPOSED, null, null, null, "no fix generated for its finding"));
                continue;
            }
            JsonNode mr = covering(decisions, byName, finding, Selection.Decision.SELECTED);
            if (mr != null) {
                String fix = mr.path("fix").asText();
                String own = decisions.stream()
                        .filter(d -> !d.path("fix").asText().equals(fix) && targets(byName, d, finding))
                        .map(d -> d.path("fix").asText() + " " + d.path("decision").asText())
                        .findFirst().map(s -> "; its own fix " + s).orElse("");
                String detail = (targets(byName, mr, finding) ? "its own fix" : "resolved by this merge request too") + own;
                rows.add(new Row(t.number(), t.title(), Status.PROPOSED, fix, mr.path("branch").asText(), test(mr), detail));
                continue;
            }
            JsonNode deferred = covering(decisions, byName, finding, Selection.Decision.DEFERRED);
            if (deferred != null) {
                rows.add(new Row(t.number(), t.title(), Status.DEFERRED, deferred.path("fix").asText(), null, null,
                        deferred.path("why").asText()));
                continue;
            }
            String why = decisions.stream().filter(d -> targets(byName, d, finding))
                    .map(d -> d.path("fix").asText() + " " + d.path("decision").asText() + ": " + d.path("why").asText())
                    .findFirst().orElse("no proven fix resolves it");
            rows.add(new Row(t.number(), t.title(), Status.NOT_PROPOSED, null, null, null, why));
        }
        if (!bundles.isEmpty()) {
            List<String> touching = decisions.stream()
                    .filter(d -> d.path("decision").asText().equals(Selection.Decision.SELECTED.name()))
                    .filter(d -> byName.containsKey(d.path("fix").asText()) && byName.get(d.path("fix").asText()).findings()
                            .stream().anyMatch(id -> bundles.containsKey(id)
                                    && bundles.get(id).path("endpoint").asText().equals(TrapScorecard.WITNESS)))
                    .map(d -> d.path("fix").asText()).toList();
            rows.add(new Row(null, "Witness " + TrapScorecard.WITNESS, touching.isEmpty() ? Status.CLEAN : Status.FLAGGED,
                    null, null, null, touching.isEmpty() ? "no merge request targets it"
                    : "targeted by " + String.join(", ", touching)));
        }
        return rows;
    }

    /** The first fix with this decision resolving the finding, its own fix (targeting it) first. */
    private static JsonNode covering(List<JsonNode> decisions, Map<String, FixEntry> fixes, String finding,
                                     Selection.Decision decision) {
        JsonNode other = null;
        for (JsonNode d : decisions) {
            FixEntry f = fixes.get(d.path("fix").asText());
            if (f == null || !d.path("decision").asText().equals(decision.name()) || !f.resolves().contains(finding)) continue;
            if (f.findings().contains(finding)) return d;
            if (other == null) other = d;
        }
        return other;
    }

    private static boolean targets(Map<String, FixEntry> fixes, JsonNode decision, String finding) {
        FixEntry f = fixes.get(decision.path("fix").asText());
        return f != null && f.findings().contains(finding);
    }

    private static String test(JsonNode mr) {
        if (mr.hasNonNull("test")) {
            String red = mr.path("red").asText();
            return "red -> green (" + mr.path("test").asText().replaceAll(".*/", "").replace(".java", "") + "): "
                    + (red.length() > 120 ? red.substring(0, 117) + "..." : red);
        }
        if (mr.hasNonNull("noTest")) return "no test: " + mr.path("noTest").asText();
        return "no test (--llm off)";
    }

    /** Traps proposed out of the traps in scope, merge requests and proven tests. */
    public static String summary(List<Row> rows) {
        long inScope = rows.stream().filter(r -> r.trap() != null && r.status() != Status.OUT_OF_SCOPE).count();
        long proposed = rows.stream().filter(r -> r.status() == Status.PROPOSED).count();
        long deferred = rows.stream().filter(r -> r.status() == Status.DEFERRED).count();
        Set<String> mrs = new LinkedHashSet<>();
        Set<String> tested = new LinkedHashSet<>();
        for (Row r : rows) {
            if (r.status() != Status.PROPOSED) continue;
            mrs.add(r.fix());
            if (r.testProven()) tested.add(r.fix());
        }
        return proposed + "/" + inScope + " traps proposed as merge requests (" + mrs.size() + " merge requests, "
                + tested.size() + " with a regression test proven red then green), " + deferred + " deferred";
    }

    public static String markdown(List<Row> rows) {
        StringBuilder md = new StringBuilder("## FlairQuery phase 5 scorecard: proposed as merge requests\n\n")
                .append(summary(rows)).append(" (demo-app/TRAPS.md)\n\n")
                .append("| # | Trap | Status | Merge request | Regression test | Detail |\n|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.trap() == null ? "✓" : r.trap()).append(" | ").append(r.title())
                    .append(" | ").append(r.status())
                    .append(" | ").append(r.branch() != null ? "`" + r.branch() + "`" : r.fix() != null ? "`" + r.fix() + "`" : "")
                    .append(" | ").append(r.test() == null ? "" : cell(r.test()))
                    .append(" | ").append(cell(r.detail())).append(" |\n");
        }
        return md.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    public static String text(List<Row> rows) {
        StringBuilder out = new StringBuilder("trap scorecard (phase 5): " + summary(rows) + "\n");
        for (Row r : rows) {
            String what = r.status() == Status.PROPOSED
                    ? r.branch() + " (" + r.detail() + ") test " + r.test()
                    : (r.fix() == null ? "" : r.fix() + ": ") + r.detail();
            out.append(String.format("  %-2s %-13s %-38s %s%n", r.trap() == null ? "W" : r.trap(), r.status(), r.title(),
                    what.replace("\n", " ")));
        }
        return out.toString();
    }
}
