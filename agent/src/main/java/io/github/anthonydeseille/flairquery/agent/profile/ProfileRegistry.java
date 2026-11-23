package io.github.anthonydeseille.flairquery.agent.profile;

import io.github.anthonydeseille.flairquery.agent.metrics.RequestStats;
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

    private final Map<String, EndpointProfile> endpoints = new ConcurrentHashMap<>();

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

    public List<Map<String, Object>> profiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        endpoints.values().stream().sorted(Comparator.comparing(e -> e.name)).forEach(e -> out.add(e.toJson()));
        return out;
    }

    static final class EndpointProfile {
        final String name;
        long requests;
        long inMemoryPaginationRequests;
        long openInViewRequests;
        final Map<String, long[]> entities = new LinkedHashMap<>();   // total, max per request
        final Map<String, Lazy> lazy = new LinkedHashMap<>();

        EndpointProfile(String name) { this.name = name; }

        synchronized void record(RequestStats st) {
            requests++;
            if (st.inMemoryPaginations() > 0) inMemoryPaginationRequests++;
            if (st.openInView()) openInViewRequests++;
            for (Map.Entry<String, int[]> e : st.entities().entrySet()) {
                long[] v = entities.get(e.getKey());
                if (v == null) {
                    if (entities.size() >= MAX_KEYS) continue;
                    entities.put(e.getKey(), v = new long[2]);
                }
                v[0] += e.getValue()[0];
                v[1] = Math.max(v[1], e.getValue()[0]);
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
            List<Map<String, Object>> ents = new ArrayList<>();
            entities.forEach((type, v) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("entity", type);
                e.put("loaded", v[0]);
                e.put("perRequest", per(v[0]));
                e.put("maxPerRequest", v[1]);
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
            m.put("inMemoryPaginationRequests", inMemoryPaginationRequests);
            m.put("openInViewRequests", openInViewRequests);
            return m;
        }

        private Double per(long total) {
            return requests == 0 ? null : Math.round(total * 100.0 / requests) / 100.0;
        }
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
