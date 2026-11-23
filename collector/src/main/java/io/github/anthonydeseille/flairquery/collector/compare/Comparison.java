package io.github.anthonydeseille.flairquery.collector.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.collector.store.Store.EndpointUse;
import io.github.anthonydeseille.flairquery.collector.store.Store.PeriodData;
import io.github.anthonydeseille.flairquery.collector.store.Store.PlanUse;
import io.github.anthonydeseille.flairquery.collector.store.Store.StatementId;
import io.github.anthonydeseille.flairquery.collector.store.Store.StatementTotals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Before / after comparison of two periods: per statement (pg_stat_statements deltas + plan shapes from
 * auto_explain), per endpoint (auto_explain attribution) and in total. Values are totals over each
 * period: they are comparable when both periods ran the same traffic (e.g. one `make traffic-local` each).
 */
public final class Comparison {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Comparison() { }

    public static Map<String, Object> of(PeriodData before, PeriodData after, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", period(before));
        out.put("after", period(after));
        out.put("totals", totals(before, after));
        out.put("endpoints", endpoints(before, after));
        out.put("statements", statements(before, after, limit));
        List<String> notes = new ArrayList<>();
        notes.add("Totals over each period: compare periods that ran the same traffic.");
        if (before.baselineOrReset() || after.baselineOrReset()) {
            notes.add("A period contains a baseline cycle, a statistics reset or evicted statements: "
                    + "some activity may be missing from it.");
        }
        if (before.snapshots() == 0 || after.snapshots() == 0) {
            notes.add("A period has no collection cycle: it is empty.");
        }
        out.put("notes", notes);
        return out;
    }

    private static Map<String, Object> period(PeriodData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", d.period().label());
        m.put("from", d.period().start().toString());
        m.put("to", d.period().end().toString());
        m.put("snapshots", d.snapshots());
        return m;
    }

    // ------------------------------------------------------------------ totals

    private static Map<String, Object> totals(PeriodData before, PeriodData after) {
        Map<String, Double> b = sum(before);
        Map<String, Double> a = sum(after);
        return withChange(b, a);
    }

    private static Map<String, Double> sum(PeriodData d) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("statements", (double) d.statements().size());
        m.put("calls", 0.0);
        m.put("totalTimeMs", 0.0);
        m.put("rows", 0.0);
        m.put("sharedBlksHit", 0.0);
        m.put("sharedBlksRead", 0.0);
        m.put("tempBlksWritten", 0.0);
        for (StatementTotals t : d.statements().values()) {
            m.merge("calls", (double) t.calls, Double::sum);
            m.merge("totalTimeMs", t.totalMs, Double::sum);
            m.merge("rows", (double) t.rows, Double::sum);
            m.merge("sharedBlksHit", (double) t.sharedBlksHit, Double::sum);
            m.merge("sharedBlksRead", (double) t.sharedBlksRead, Double::sum);
            m.merge("tempBlksWritten", (double) t.tempBlksWritten, Double::sum);
        }
        return m;
    }

    private static Map<String, Double> values(StatementTotals t) {
        if (t == null) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("calls", (double) t.calls);
        m.put("totalTimeMs", t.totalMs);
        m.put("meanTimeMs", t.calls == 0 ? 0 : t.totalMs / t.calls);
        m.put("rows", (double) t.rows);
        m.put("sharedBlksHit", (double) t.sharedBlksHit);
        m.put("sharedBlksRead", (double) t.sharedBlksRead);
        m.put("tempBlksWritten", (double) t.tempBlksWritten);
        return m;
    }

    /** {"before":{...},"after":{...},"changePct":{...}}; changePct is null where "before" is 0. */
    static Map<String, Object> withChange(Map<String, Double> b, Map<String, Double> a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", rounded(b));
        m.put("after", rounded(a));
        Map<String, Object> pct = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        if (b != null) keys.addAll(b.keySet());
        if (a != null) keys.addAll(a.keySet());
        for (String k : keys) {
            pct.put(k, changePct(b == null ? null : b.get(k), a == null ? null : a.get(k)));
        }
        m.put("changePct", pct);
        return m;
    }

    static Double changePct(Double before, Double after) {
        double b = before == null ? 0 : before;
        double a = after == null ? 0 : after;
        if (b == 0) return null;
        return Math.round((a - b) / b * 1000.0) / 10.0;
    }

    private static Map<String, Object> rounded(Map<String, Double> v) {
        if (v == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        v.forEach((k, x) -> m.put(k, x == Math.rint(x) && Math.abs(x) < 1e15 ? (Object) (long) (double) x
                : (Object) (Math.round(x * 1000.0) / 1000.0)));
        return m;
    }

    // ------------------------------------------------------------------ endpoints (auto_explain)

    private static List<Map<String, Object>> endpoints(PeriodData before, PeriodData after) {
        Map<String, Map<String, Double>> b = perEndpoint(before);
        Map<String, Map<String, Double>> a = perEndpoint(after);
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", k);
            m.putAll(withChange(b.get(k), a.get(k)));
            out.add(m);
        }
        out.sort(Comparator.comparingDouble(m -> -Math.abs(gain(m, "loggedTimeMs"))));
        return out;
    }

    private static Map<String, Map<String, Double>> perEndpoint(PeriodData d) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        Map<String, Set<StatementId>> distinct = new LinkedHashMap<>();
        for (EndpointUse e : d.endpoints()) {
            String key = e.endpoint() == null ? "(untagged)" : e.endpoint();
            Map<String, Double> m = out.computeIfAbsent(key, x -> {
                Map<String, Double> v = new LinkedHashMap<>();
                v.put("executions", 0.0);
                v.put("loggedTimeMs", 0.0);
                v.put("distinctStatements", 0.0);
                return v;
            });
            m.merge("executions", (double) e.executions(), Double::sum);
            m.merge("loggedTimeMs", e.totalMs(), Double::sum);
            distinct.computeIfAbsent(key, x -> new HashSet<>()).add(e.statement());
        }
        distinct.forEach((k, s) -> out.get(k).put("distinctStatements", (double) s.size()));
        return out;
    }

    // ------------------------------------------------------------------ statements

    private static List<Map<String, Object>> statements(PeriodData before, PeriodData after, int limit) {
        Set<Long> ids = new LinkedHashSet<>(before.statements().keySet());
        ids.addAll(after.statements().keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (long id : ids) {
            StatementTotals b = before.statements().get(id);
            StatementTotals a = after.statements().get(id);
            StatementTotals any = b != null ? b : a;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("queryid", Long.toString(any.id.queryid()));
            m.put("database", any.id.db());
            m.put("role", any.id.role());
            m.put("sql", any.sql);
            m.put("status", b == null ? "new" : a == null ? "gone" : "both");
            m.put("endpoints", endpointsOf(any.id, before, after, any.endpoint));
            m.putAll(withChange(values(b), values(a)));
            m.put("plans", plans(any.id, before, after));
            if ((b != null && b.restarted) || (a != null && a.restarted)) m.put("countersRestarted", true);
            out.add(m);
        }
        out.sort(Comparator.comparingDouble(m -> -Math.abs(gain(m, "totalTimeMs"))));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static List<String> endpointsOf(StatementId id, PeriodData before, PeriodData after, String firstTag) {
        Set<String> out = new LinkedHashSet<>();
        for (PeriodData d : List.of(before, after)) {
            for (EndpointUse e : d.endpoints()) {
                if (e.statement().equals(id) && e.endpoint() != null) out.add(e.endpoint());
            }
        }
        if (out.isEmpty() && firstTag != null) out.add(firstTag);   // no auto_explain entry: first tag seen
        return new ArrayList<>(out);
    }

    private static Map<String, Object> plans(StatementId id, PeriodData before, PeriodData after) {
        List<Map<String, Object>> b = plansOf(id, before);
        List<Map<String, Object>> a = plansOf(id, after);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", b);
        m.put("after", a);
        Set<Object> fb = new HashSet<>();
        b.forEach(p -> fb.add(p.get("fingerprint")));
        Set<Object> fa = new HashSet<>();
        a.forEach(p -> fa.add(p.get("fingerprint")));
        m.put("changed", !b.isEmpty() && !a.isEmpty() && !fb.equals(fa));
        return m;
    }

    private static List<Map<String, Object>> plansOf(StatementId id, PeriodData d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanUse p : d.plans()) {
            if (p.statement().db().equals(id.db()) && p.statement().queryid() == id.queryid()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fingerprint", p.fingerprint());
                m.put("executions", p.executions());
                m.put("summary", parse(p.summary()));
                out.add(m);
            }
        }
        return out;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.getNodeFactory().textNode(json);
        }
    }

    @SuppressWarnings("unchecked")
    private static double gain(Map<String, Object> m, String key) {
        Object b = m.get("before") == null ? null : ((Map<String, Object>) m.get("before")).get(key);
        Object a = m.get("after") == null ? null : ((Map<String, Object>) m.get("after")).get(key);
        return (a == null ? 0 : ((Number) a).doubleValue()) - (b == null ? 0 : ((Number) b).doubleValue());
    }
}
