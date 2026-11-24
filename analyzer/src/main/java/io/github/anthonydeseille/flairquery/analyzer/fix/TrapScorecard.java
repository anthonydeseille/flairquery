package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 4 scorecard of the demo app's traps (ground truth: {@code demo-app/TRAPS.md}): for each trap, whether a
 * fix was generated, proven and by which generator, with the proof of its endpoint (statements and SQL time per
 * request before / after). A trap is FIXED only when an ACCEPTED fix resolves its finding. Trap 7 is out of phase
 * 4 (keyset pagination changes the API; {@code work_mem} is phase 6). The witness stays CLEAN when no fix
 * targets it (every proven fix also kept its JSON identical: the prover replays it).
 */
public final class TrapScorecard {

    public enum Status { FIXED, NOT_FIXED, NOT_GENERATED, NOT_ANALYZED, OUT_OF_SCOPE, CLEAN, FLAGGED }

    static final String DEMO = "io.github.anthonydeseille.flairquery.demo.domain.";
    public static final String WITNESS = "GET /publishers/{id}";

    /** A trap and the finding a fix must resolve (null: out of scope). */
    public record Trap(int number, String title, String finding) { }

    public static final List<Trap> TRAPS = List.of(
            new Trap(1, "N+1 authors -> books", BundleBuilder.id("N_PLUS_ONE_LAZY", "GET /authors", "Author.books")),
            new Trap(2, "@ManyToOne EAGER publisher", BundleBuilder.id("USELESS_EAGER", "GET /books", "Book.publisher")),
            new Trap(3, "Over-fetching (book description)", BundleBuilder.id("OVER_FETCHING", "GET /books/titles", DEMO + "Book")),
            new Trap(4, "Missing index on review.reviewer_name",
                    BundleBuilder.id("MISSING_INDEX", "GET /reviews/search", "review.reviewer_name")),
            new Trap(5, "JOIN FETCH + pagination", BundleBuilder.id("IN_MEMORY_PAGINATION", "GET /authors/with-books", "")),
            new Trap(6, "open-in-view", BundleBuilder.id("OPEN_IN_VIEW", "GET /authors", "")),
            new Trap(7, "Sort spilling to disk", null));

    /**
     * @param fix       patch name of the fix that settles the trap's status (proven one first), null if none
     * @param generator its generator ({@code llm}, {@code lazy-association}...)
     * @param attempt   1 for a first fix, more after retries
     * @param detail    the proof of the endpoint, or why the trap is not fixed
     */
    public record Row(Integer trap, String title, Status status, String fix, String generator, int attempt, String detail) { }

    private TrapScorecard() { }

    /** Whether the analyzed findings are the demo app's (at least one trap finding among them). */
    public static boolean isDemo(Collection<String> findings) {
        return TRAPS.stream().anyMatch(t -> t.finding() != null && findings.contains(t.finding()));
    }

    /**
     * @param outcomes every fix proven by the run (all attempts)
     * @param analyzed ids of the findings the run generated fixes for (its bundles)
     */
    public static List<Row> of(List<FixCommand.Outcome> outcomes, Set<String> analyzed) {
        List<Row> rows = new ArrayList<>();
        for (Trap t : TRAPS) {
            if (t.finding() == null) {
                rows.add(new Row(t.number(), t.title(), Status.OUT_OF_SCOPE, null, null, 0,
                        "keyset pagination changes the API; work_mem is phase 6"));
                continue;
            }
            if (!analyzed.contains(t.finding())) {
                rows.add(new Row(t.number(), t.title(), Status.NOT_ANALYZED, null, null, 0, "finding not among the bundles"));
                continue;
            }
            FixCommand.Outcome proven = outcomes.stream()
                    .filter(o -> o.verdict() != null && o.verdict().accepted() && resolves(o, t.finding()))
                    .min((a, b) -> Boolean.compare(!targets(a, t.finding()), !targets(b, t.finding())))
                    .orElse(null);
            if (proven != null) {
                rows.add(row(t, Status.FIXED, proven, proof(proven.verdict().report(), endpoint(proven, t.finding()))));
                continue;
            }
            List<FixCommand.Outcome> tried = outcomes.stream().filter(o -> targets(o, t.finding())).toList();
            if (tried.isEmpty()) {
                rows.add(new Row(t.number(), t.title(), Status.NOT_GENERATED, null, null, 0, "no fix generated"));
            } else {
                FixCommand.Outcome last = tried.get(tried.size() - 1);
                String why = last.verdict() == null ? "not proven"
                        : last.verdict().status() + ": " + String.join("; ", last.verdict().reasons());
                rows.add(row(t, Status.NOT_FIXED, last, why));
            }
        }
        boolean flagged = outcomes.stream().anyMatch(o -> o.proposal().bundles().stream()
                .anyMatch(b -> b.path("endpoint").asText().equals(WITNESS)));
        rows.add(new Row(null, "Witness " + WITNESS, flagged ? Status.FLAGGED : Status.CLEAN, null, null, 0,
                flagged ? "a fix targets the witness" : "no fix targets it; JSON identical under every proven fix"));
        return rows;
    }

    private static Row row(Trap t, Status s, FixCommand.Outcome o, String detail) {
        return new Row(t.number(), t.title(), s, o.name(), o.proposal().fix().generator(), o.proposal().attempt(), detail);
    }

    private static boolean targets(FixCommand.Outcome o, String finding) {
        return o.proposal().findings().contains(finding);
    }

    private static boolean resolves(FixCommand.Outcome o, String finding) {
        for (JsonNode r : o.verdict().report().path("findingsResolved")) {
            if (r.path("id").asText().equals(finding)) return true;
        }
        return false;
    }

    /** The endpoint of a finding, from the bundles of the fix (null when the fix does not target it). */
    private static String endpoint(FixCommand.Outcome o, String finding) {
        return o.proposal().bundles().stream().filter(b -> b.path("id").asText().equals(finding))
                .map(b -> b.path("endpoint").asText()).findFirst().orElse(null);
    }

    /**
     * An endpoint before -> after, from a proof report: the trap's own endpoint when known (one fix may resolve
     * findings on several endpoints), else the endpoint the verdict was given for.
     */
    static String proof(JsonNode report, String endpoint) {
        for (JsonNode e : report.path("endpoints")) {
            if (endpoint == null ? !e.path("target").asBoolean() : !e.path("endpoint").asText().equals(endpoint)) continue;
            JsonNode b = e.path("before");
            JsonNode a = e.path("after");
            return String.format(Locale.ROOT, "%s: %s -> %s statements, %.1f -> %.1f ms SQL per request",
                    e.path("endpoint").asText(), count(b), count(a),
                    b.path("sqlMsPerRequest").asDouble(), a.path("sqlMsPerRequest").asDouble());
        }
        return "proven";
    }

    private static String count(JsonNode cost) {
        double n = cost.path("statementsPerRequest").asDouble();
        return n == Math.rint(n) ? String.valueOf((long) n) : String.format(Locale.ROOT, "%.2f", n);
    }

    /** Fixed traps out of the traps in scope. */
    public static String summary(List<Row> rows) {
        long inScope = rows.stream().filter(r -> r.trap() != null && r.status() != Status.OUT_OF_SCOPE).count();
        long fixed = rows.stream().filter(r -> r.status() == Status.FIXED).count();
        return fixed + "/" + inScope + " traps fixed and proven";
    }

    public static String markdown(List<Row> rows) {
        StringBuilder md = new StringBuilder("## FlairQuery phase 4 scorecard: fixed and proven\n\n")
                .append(summary(rows)).append(" (demo-app/TRAPS.md)\n\n")
                .append("| # | Trap | Status | Fix | Generator | Attempt | Proof |\n|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.trap() == null ? "✓" : r.trap()).append(" | ").append(r.title())
                    .append(" | ").append(r.status()).append(" | ").append(r.fix() == null ? "" : "`" + r.fix() + "`")
                    .append(" | ").append(r.generator() == null ? "" : r.generator())
                    .append(" | ").append(r.attempt() == 0 ? "" : r.attempt())
                    .append(" | ").append(r.detail().replace("|", "\\|")).append(" |\n");
        }
        return md.toString();
    }

    public static String text(List<Row> rows) {
        StringBuilder out = new StringBuilder("trap scorecard (phase 4): " + summary(rows) + "\n");
        for (Row r : rows) {
            out.append(String.format("  %-2s %-14s %-38s %s%n", r.trap() == null ? "W" : r.trap(), r.status(), r.title(),
                    r.fix() == null ? r.detail() : r.fix() + " [" + r.generator() + (r.attempt() > 1 ? ", attempt " + r.attempt() : "")
                            + "] " + r.detail()));
        }
        return out.toString();
    }
}
