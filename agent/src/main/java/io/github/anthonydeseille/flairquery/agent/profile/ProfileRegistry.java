package io.github.anthonydeseille.flairquery.agent.profile;

import io.github.anthonydeseille.flairquery.agent.metrics.RequestStats;
import io.github.anthonydeseille.flairquery.agent.model.EntityModel;
import io.github.anthonydeseille.flairquery.agent.model.EntityModels;
import io.github.anthonydeseille.flairquery.agent.spi.FqListener;
import io.github.anthonydeseille.flairquery.agent.spi.RequestSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint JPA profile (phase 3), aggregated from profiled requests: entities put into the persistence
 * context, lazy loads (and how many ran outside any Spring transaction), in-memory pagination, open-in-view.
 * Bounded like the other registries; one lock per endpoint (requests of one endpoint are short and rare
 * enough for a synchronized aggregation).
 */
public final class ProfileRegistry implements FqListener {

    static final int MAX_ENDPOINTS = 500;
    static final int MAX_KEYS = 200;
    static final int MAX_STATEMENTS_SHOWN = 20;

    private final Map<String, EndpointProfile> endpoints = new ConcurrentHashMap<>();
    private final int nPlusOneThreshold;

    public ProfileRegistry() {
        this(5);
    }

    public ProfileRegistry(int nPlusOneThreshold) {
        this.nPlusOneThreshold = nPlusOneThreshold;
    }

    @Override
    public void onRequest(RequestSummary s) {
        if (!s.profiled()) {
            return;
        }
        EndpointProfile p = endpoints.get(s.endpoint());
        if (p == null) {
            if (endpoints.size() >= MAX_ENDPOINTS) {
                return;
            }
            p = endpoints.computeIfAbsent(s.endpoint(), EndpointProfile::new);
        }
        p.record(s.stats());
    }

    /** Per-endpoint profiles, each with its findings. */
    public List<Map<String, Object>> profiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        endpoints.values().stream().sorted(Comparator.comparing(e -> e.name)).forEach(e -> {
            Map<String, Object> p = e.toJson();
            p.put("findings", Findings.of(p, nPlusOneThreshold));
            out.add(p);
        });
        return out;
    }

    /** Every finding of every endpoint, most severe first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : profiles()) {
            out.addAll((List<Map<String, Object>>) p.get("findings"));
        }
        out.sort(Comparator.comparingInt(f -> Findings.SEVERITY_RANK.get((String) f.get("severity"))));
        return out;
    }

    static final class EndpointProfile {
        final String name;
        long requests;
        long queries;
        long rows;
        long sqlNanos;
        long maxRowsPerRequest;
        long inMemoryPaginationRequests;
        long openInViewRequests;
        final Map<String, long[]> entities = new LinkedHashMap<>();   // total, max per request, requests seen
        /** Per entity class: number of requests in which each attribute was read. */
        final Map<String, long[]> attributeReads = new LinkedHashMap<>();
        final Map<String, Lazy> lazy = new LinkedHashMap<>();
        final Map<String, Statement> statements = new LinkedHashMap<>();

        EndpointProfile(String name) { this.name = name; }

        synchronized void record(RequestStats st) {
            requests++;
            queries += st.queries();
            rows += st.rows();
            sqlNanos += st.sqlNanos();
            maxRowsPerRequest = Math.max(maxRowsPerRequest, st.rows());
            for (Map.Entry<String, RequestStats.Entry> e : st.bySql().entrySet()) {
                Statement x = statements.get(e.getKey());
                if (x == null) {
                    if (statements.size() >= MAX_KEYS) continue;
                    statements.put(e.getKey(), x = new Statement());
                }
                RequestStats.Entry v = e.getValue();
                x.executions += v.count();
                x.maxPerRequest = Math.max(x.maxPerRequest, v.count());
                x.rows += v.rows();
                x.nanos += v.nanos();
                x.caller = v.caller();
                if (v.lazyRole() != null) x.lazyRole = v.lazyRole();
            }
            if (st.inMemoryPaginations() > 0) inMemoryPaginationRequests++;
            if (st.openInView()) openInViewRequests++;
            java.util.Set<String> seen = new java.util.HashSet<>(st.entities().keySet());
            seen.addAll(st.attributesRead().keySet());
            for (String type : seen) {
                long[] v = entities.get(type);
                if (v == null) {
                    if (entities.size() >= MAX_KEYS) continue;
                    entities.put(type, v = new long[3]);
                }
                int[] loaded = st.entities().get(type);
                int n = loaded == null ? 0 : loaded[0];
                v[0] += n;
                v[1] = Math.max(v[1], n);
                v[2]++;
                java.util.BitSet read = st.attributesRead().get(type);
                EntityModel model = EntityModels.of(type);
                if (model != null) {
                    long[] reads = attributeReads.computeIfAbsent(type, k -> new long[model.attributes().size()]);
                    if (read != null) {
                        for (int i = read.nextSetBit(0); i >= 0 && i < reads.length; i = read.nextSetBit(i + 1)) {
                            reads[i]++;
                        }
                    }
                }
            }
            for (Map.Entry<String, RequestStats.LazyLoad> e : st.lazyLoads().entrySet()) {
                Lazy l = lazy.get(e.getKey());
                if (l == null) {
                    if (lazy.size() >= MAX_KEYS) continue;
                    lazy.put(e.getKey(), l = new Lazy(e.getValue().kind()));
                }
                RequestStats.LazyLoad v = e.getValue();
                l.count += v.count();
                l.outsideTransaction += v.outsideTransaction();
                l.nanos += v.nanos();
                l.maxPerRequest = Math.max(l.maxPerRequest, v.count());
                l.caller = v.caller();
            }
        }

        synchronized Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", name);
            m.put("profiledRequests", requests);
            m.put("queriesPerRequest", per(queries));
            m.put("rowsPerRequest", per(rows));
            m.put("maxRowsPerRequest", maxRowsPerRequest);
            m.put("sqlTimeMsPerRequest", requests == 0 ? null : Math.round(sqlNanos / 1_000.0 / requests) / 1_000.0);
            List<Map<String, Object>> ents = new ArrayList<>();
            entities.forEach((type, v) -> {
                if (v[0] == 0 && attributeReads.get(type) == null) return;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("entity", type);
                e.put("loaded", v[0]);
                e.put("perRequest", per(v[0]));
                e.put("maxPerRequest", v[1]);
                EntityModel model = EntityModels.of(type);
                long[] reads = attributeReads.get(type);
                if (model != null && !model.propertyAccess() && reads != null) {
                    List<Map<String, Object>> attrs = new ArrayList<>();
                    List<String> unread = new ArrayList<>();
                    for (EntityModel.Attribute a : model.attributes()) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("name", a.name());
                        x.put("kind", a.kind().name());
                        if (a.fetch() != null) {
                            x.put("fetch", a.fetch());
                            x.put("fetchExplicit", a.fetchExplicit());
                        }
                        if (a.large()) x.put("large", true);
                        if (a.targetType() != null) x.put("target", a.targetType());
                        x.put("readRatio", Math.round(reads[a.index()] * 100.0 / v[2]) / 100.0);
                        attrs.add(x);
                        if (reads[a.index()] == 0 && a.kind() != EntityModel.Kind.ID) unread.add(a.name());
                    }
                    e.put("attributes", attrs);
                    e.put("neverRead", unread);
                } else if (model != null && model.propertyAccess()) {
                    e.put("attributes", "not tracked (property access: Hibernate calls the getters itself)");
                }
                ents.add(e);
            });
            ents.sort(Comparator.comparingLong(e -> -(long) e.get("loaded")));
            m.put("entities", ents);
            List<Map<String, Object>> lazies = new ArrayList<>();
            lazy.forEach((role, l) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("role", role);
                e.put("kind", l.kind);
                e.put("loads", l.count);
                e.put("perRequest", per(l.count));
                e.put("maxPerRequest", l.maxPerRequest);
                e.put("outsideTransaction", l.outsideTransaction);
                e.put("timeMs", Math.round(l.nanos / 1_000.0) / 1_000.0);
                e.put("caller", l.caller);
                lazies.add(e);
            });
            lazies.sort(Comparator.comparingLong(e -> -(long) e.get("loads")));
            m.put("lazyLoads", lazies);
            List<Map<String, Object>> stmts = new ArrayList<>();
            statements.forEach((sql, x) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("sql", sql);
                e.put("caller", x.caller);
                e.put("executions", x.executions);
                e.put("perRequest", per(x.executions));
                e.put("maxPerRequest", x.maxPerRequest);
                e.put("rowsPerExecution", x.executions == 0 ? null : Math.round(x.rows * 100.0 / x.executions) / 100.0);
                e.put("timeMs", Math.round(x.nanos / 1_000.0) / 1_000.0);
                e.put("triggeredByLazyLoad", x.lazyRole);
                stmts.add(e);
            });
            stmts.sort(Comparator.comparingLong(e -> -(long) e.get("executions")));
            m.put("statements", stmts.size() > MAX_STATEMENTS_SHOWN ? stmts.subList(0, MAX_STATEMENTS_SHOWN) : stmts);
            m.put("inMemoryPaginationRequests", inMemoryPaginationRequests);
            m.put("openInViewRequests", openInViewRequests);
            return m;
        }

        private Double per(long total) {
            return requests == 0 ? null : Math.round(total * 100.0 / requests) / 100.0;
        }
    }

    static final class Statement {
        long executions;
        long maxPerRequest;
        long rows;
        long nanos;
        String caller;
        String lazyRole;
    }

    static final class Lazy {
        final String kind;
        long count;
        long outsideTransaction;
        long nanos;
        long maxPerRequest;
        String caller;

        Lazy(String kind) { this.kind = kind; }
    }
}
