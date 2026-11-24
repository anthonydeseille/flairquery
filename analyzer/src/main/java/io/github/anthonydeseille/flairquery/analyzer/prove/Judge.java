package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a fix is proven, from a measurement without it (baseline) and one with it (candidate), both
 * taken on clones of the same database. A fix is accepted only when:
 * <ol>
 *   <li>every response has the same HTTP status and, for deterministic endpoints, a strictly identical JSON
 *       body (objects compared field by field whatever the order, arrays in order);</li>
 *   <li>the finding it targets is gone;</li>
 *   <li>no new finding appears;</li>
 *   <li>the finding's endpoint is cheaper: fewer statements <em>and</em> less SQL time per request, or the same
 *       statements with at least {@link Thresholds#minTimeGain} less SQL time (an index changes plans, not
 *       statement counts);</li>
 *   <li>no other endpoint runs more statements, or takes noticeably more SQL time
 *       ({@link Thresholds#timeNoise} + {@link Thresholds#timeNoiseMs}). An endpoint that runs exactly the same
 *       SQL (same statements, same count per request) while the patch changes no migration cannot be slowed
 *       down by the patch at the SQL level: its time difference is measurement noise, reported but not held
 *       against the fix (a deep sort spilling to disk varies by +-40 % between runs on a laptop).</li>
 * </ol>
 * Pure logic: no I/O, unit-tested on synthetic measurements. Reports name JSON paths, never data values.
 */
public final class Judge {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Statements per request are averages rounded to 0.01 by the agent. */
    static final double STATEMENT_EPSILON = 0.05;

    /**
     * @param minTimeGain fraction of SQL time the target endpoint must save when its statement count is unchanged
     * @param timeNoise   relative SQL time increase tolerated on the other endpoints (measurement noise)
     * @param timeNoiseMs absolute SQL time increase per request tolerated on top of {@code timeNoise}
     */
    public record Thresholds(double minTimeGain, double timeNoise, double timeNoiseMs) {
        public static final Thresholds DEFAULT = new Thresholds(0.20, 0.30, 2.0);
    }

    private Judge() { }

    public static Verdict judge(String findingId, Measurement before, Measurement after, Thresholds t) {
        return judge(findingId, before, after, t, false);
    }

    /** @param schemaChanged the patch touches a database migration: identical SQL may get another plan */
    public static Verdict judge(String findingId, Measurement before, Measurement after, Thresholds t,
                                boolean schemaChanged) {
        ObjectNode report = JSON.createObjectNode();
        report.put("finding", findingId);
        ArrayNode checks = report.putArray("checks");
        List<String> reasons = new ArrayList<>();

        JsonNode finding = before.findings().get(findingId);
        if (finding == null) {
            String why = "finding " + findingId + " does not show up without the fix on the test database";
            check(checks, "finding reproduced", false, why);
            report.put("status", Verdict.Status.INCONCLUSIVE.name());
            return new Verdict(Verdict.Status.INCONCLUSIVE, List.of(why), report);
        }
        String target = finding.path("endpoint").asText();
        report.put("kind", finding.path("kind").asText());
        report.put("endpoint", target);
        report.put("subject", BundleBuilder.subject(finding.path("kind").asText(), finding.path("evidence")));

        responses(before, after, checks, reasons);
        findings(findingId, before, after, report, checks, reasons);
        costs(target, before, after, t, schemaChanged, report, checks, reasons);

        Verdict.Status status = reasons.isEmpty() ? Verdict.Status.ACCEPTED : Verdict.Status.REJECTED;
        report.put("status", status.name());
        report.set("reasons", JSON.valueToTree(reasons));
        return new Verdict(status, List.copyOf(reasons), report);
    }

    private static void responses(Measurement before, Measurement after, ArrayNode checks, List<String> reasons) {
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, Measurement.Response> e : before.responses().entrySet()) {
            String path = e.getKey();
            Measurement.Response b = e.getValue();
            Measurement.Response a = after.responses().get(path);
            if (a == null) {
                diffs.add(path + ": not replayed");
            } else if (a.status() != b.status()) {
                diffs.add(path + ": HTTP " + b.status() + " before, " + a.status() + " after");
            } else if (b.json() != null || a.json() != null) {
                String where = b.json() == null || a.json() == null ? "" : firstDifference(b.json(), a.json(), "");
                if (b.json() == null || a.json() == null) diffs.add(path + ": body is no longer JSON");
                else if (where != null) diffs.add(path + ": JSON differs at " + (where.isEmpty() ? "/" : where));
            }
        }
        boolean ok = diffs.isEmpty();
        check(checks, "identical responses", ok, ok
                ? before.responses().size() + " request(s), same status, JSON strictly identical where compared"
                : String.join("; ", diffs));
        diffs.forEach(d -> reasons.add("response changed: " + d));
    }

    private static void findings(String findingId, Measurement before, Measurement after, ObjectNode report,
                                 ArrayNode checks, List<String> reasons) {
        boolean gone = !after.findings().containsKey(findingId);
        check(checks, "finding gone", gone, gone ? findingId + " no longer reported"
                : findingId + " still reported: " + after.findings().get(findingId).path("title").asText());
        if (!gone) reasons.add("the finding is still reported");

        Set<String> resolved = new TreeSet<>(before.findings().keySet());
        resolved.removeAll(after.findings().keySet());
        Set<String> added = new TreeSet<>(after.findings().keySet());
        added.removeAll(before.findings().keySet());
        ArrayNode r = report.putArray("findingsResolved");
        resolved.forEach(id -> r.add(summary(id, before.findings().get(id))));
        ArrayNode n = report.putArray("findingsNew");
        added.forEach(id -> n.add(summary(id, after.findings().get(id))));
        check(checks, "no new finding", added.isEmpty(), added.isEmpty() ? "none"
                : added.stream().map(id -> describe(after.findings().get(id))).toList().toString());
        added.forEach(id -> reasons.add("new finding: " + describe(after.findings().get(id))));
    }

    private static void costs(String target, Measurement before, Measurement after, Thresholds t,
                              boolean schemaChanged, ObjectNode report, ArrayNode checks, List<String> reasons) {
        report.put("schemaChanged", schemaChanged);
        ArrayNode table = report.putArray("endpoints");
        List<String> regressions = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.endpoints().keySet());
        names.addAll(after.endpoints().keySet());
        for (String endpoint : names) {
            Measurement.EndpointCost b = before.endpoints().get(endpoint);
            Measurement.EndpointCost a = after.endpoints().get(endpoint);
            ObjectNode row = table.addObject();
            row.put("endpoint", endpoint);
            row.put("target", endpoint.equals(target));
            if (b != null) row.set("before", cost(b));
            if (a != null) row.set("after", cost(a));
            if (b == null || a == null) continue;
            boolean sameSql = !b.statements().isEmpty() && b.statements().equals(a.statements());
            row.put("sameSql", sameSql);
            if (endpoint.equals(target)) continue;
            boolean slower = a.sqlMsPerRequest() > b.sqlMsPerRequest() * (1 + t.timeNoise()) + t.timeNoiseMs();
            if (a.statementsPerRequest() > b.statementsPerRequest() + STATEMENT_EPSILON) {
                regressions.add(String.format(java.util.Locale.ROOT, "%s: %.2f statements per request instead of %.2f",
                        endpoint, a.statementsPerRequest(), b.statementsPerRequest()));
            } else if (slower && sameSql && !schemaChanged) {
                row.put("note", "slower, but the same SQL on the same schema: measurement noise");
            } else if (slower) {
                regressions.add(String.format(java.util.Locale.ROOT, "%s: %.3f ms SQL per request instead of %.3f",
                        endpoint, a.sqlMsPerRequest(), b.sqlMsPerRequest()));
            }
        }

        Measurement.EndpointCost b = before.endpoints().get(target);
        Measurement.EndpointCost a = after.endpoints().get(target);
        String detail;
        boolean improved;
        if (b == null || a == null) {
            improved = false;
            detail = target + " was not measured " + (b == null ? "without" : "with") + " the fix";
        } else {
            boolean fewer = a.statementsPerRequest() < b.statementsPerRequest() - STATEMENT_EPSILON;
            boolean same = Math.abs(a.statementsPerRequest() - b.statementsPerRequest()) <= STATEMENT_EPSILON;
            boolean faster = a.sqlMsPerRequest() < b.sqlMsPerRequest();
            boolean muchFaster = a.sqlMsPerRequest() <= b.sqlMsPerRequest() * (1 - t.minTimeGain());
            improved = (fewer && faster) || (same && muchFaster);
            detail = String.format(java.util.Locale.ROOT,
                    "%s: %.2f -> %.2f statements, %.3f -> %.3f ms SQL per request (%s)", target,
                    b.statementsPerRequest(), a.statementsPerRequest(), b.sqlMsPerRequest(), a.sqlMsPerRequest(),
                    pct(b.sqlMsPerRequest(), a.sqlMsPerRequest()));
        }
        check(checks, "endpoint cheaper", improved, detail);
        if (!improved) reasons.add("no measurable gain: " + detail);

        check(checks, "no regression elsewhere", regressions.isEmpty(),
                regressions.isEmpty() ? "none" : String.join("; ", regressions));
        regressions.forEach(r -> reasons.add("regression on " + r));
    }

    /** JSON pointer of the first difference, null when equal. */
    static String firstDifference(JsonNode a, JsonNode b, String at) {
        if (a.getNodeType() != b.getNodeType()) return at;
        if (a.isObject()) {
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                String p = at + "/" + k.replace("~", "~0").replace("/", "~1");
                if (!a.has(k) || !b.has(k)) return p;
                String d = firstDifference(a.get(k), b.get(k), p);
                if (d != null) return d;
            }
            return null;
        }
        if (a.isArray()) {
            Iterator<JsonNode> x = a.elements();
            Iterator<JsonNode> y = b.elements();
            int i = 0;
            while (x.hasNext() && y.hasNext()) {
                String d = firstDifference(x.next(), y.next(), at + "/" + i++);
                if (d != null) return d;
            }
            return x.hasNext() || y.hasNext() ? at + "/" + i : null;
        }
        return a.equals(b) ? null : at;
    }

    private static ObjectNode cost(Measurement.EndpointCost c) {
        ObjectNode o = JSON.createObjectNode();
        o.put("requests", c.requests());
        o.put("statementsPerRequest", Math.round(c.statementsPerRequest() * 100) / 100.0);
        o.put("sqlMsPerRequest", Math.round(c.sqlMsPerRequest() * 1000) / 1000.0);
        return o;
    }

    private static String pct(double before, double after) {
        if (before <= 0) return "n/a";
        return String.format(java.util.Locale.ROOT, "%+.1f %%", (after - before) * 100 / before);
    }

    private static ObjectNode summary(String id, JsonNode f) {
        ObjectNode o = JSON.createObjectNode();
        o.put("id", id);
        o.put("kind", f.path("kind").asText());
        o.put("endpoint", f.path("endpoint").asText());
        o.put("title", f.path("title").asText());
        return o;
    }

    private static String describe(JsonNode f) {
        return f.path("kind").asText() + " on " + f.path("endpoint").asText() + " (" + f.path("title").asText() + ")";
    }

    private static void check(ArrayNode checks, String name, boolean passed, String detail) {
        ObjectNode c = checks.addObject();
        c.put("check", name);
        c.put("passed", passed);
        c.put("detail", detail);
    }
}
