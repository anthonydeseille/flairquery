package io.github.anthonydeseille.flairquery.agent.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detection rules of the JPA profile (phase 3). A pure function of one endpoint's aggregated profile (the
 * {@code /fq/profile} JSON shape): every finding carries its evidence (measured numbers, caller) and the
 * kind of fix expected. The fix itself is phase 4's job (proposed, then proven).
 *
 * <ul>
 *   <li>{@code N_PLUS_ONE_LAZY}: a lazy association loaded more than the threshold per request</li>
 *   <li>{@code USELESS_EAGER}: an EAGER association never read while its targets are loaded</li>
 *   <li>{@code OVER_FETCHING}: entities loaded whose large columns, or most columns, are never read</li>
 *   <li>{@code IN_MEMORY_PAGINATION}: Hibernate paginated in memory (collection fetch + first/max results)</li>
 *   <li>{@code OPEN_IN_VIEW}: lazy loading outside any transaction, in the web layer, through open-in-view</li>
 * </ul>
 */
public final class Findings {

    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";
    static final Map<String, Integer> SEVERITY_RANK = Map.of(HIGH, 0, MEDIUM, 1, LOW, 2);

    /** Over-fetching on unread small columns only counts from this many entities per request. */
    static final double OVER_FETCH_MIN_ENTITIES = 10;

    private Findings() { }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> of(Map<String, Object> profile, int nPlusOneThreshold) {
        List<Map<String, Object>> out = new ArrayList<>();
        String endpoint = (String) profile.get("endpoint");
        List<Map<String, Object>> entities = list(profile.get("entities"));
        List<Map<String, Object>> lazy = list(profile.get("lazyLoads"));
        List<Map<String, Object>> statements = list(profile.get("statements"));

        // ------------------------------------------------------------ N+1 through lazy loading
        for (Map<String, Object> l : lazy) {
            if (num(l.get("maxPerRequest")) <= nPlusOneThreshold) continue;
            String role = (String) l.get("role");
            String name = "proxy".equals(l.get("kind")) ? simple(role) : shortRole(role);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("association", name);
            ev.put("lazyLoadsPerRequest", l.get("perRequest"));
            ev.put("maxPerRequest", l.get("maxPerRequest"));
            ev.put("caller", l.get("caller"));
            List<Map<String, Object>> triggered = new ArrayList<>();
            for (Map<String, Object> s : statements) {
                if (role.equals(s.get("triggeredByLazyLoad"))) {
                    triggered.add(Map.of("sql", s.get("sql"), "perRequest", s.get("perRequest"),
                            "timeMs", s.get("timeMs")));
                }
            }
            ev.put("statements", triggered);
            out.add(finding(endpoint, "N_PLUS_ONE_LAZY", num(l.get("perRequest")) >= 20 ? HIGH : MEDIUM,
                    "N+1: " + name + " is loaded lazily " + fmt(l.get("perRequest")) + " times per request",
                    ev, "Fetch it with the parent query (@EntityGraph(attributePaths = \"" + attribute(role)
                            + "\") or JOIN FETCH), or batch it (@BatchSize / hibernate.default_batch_fetch_size)"));
        }

        // ------------------------------------------------------------ entity attributes
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        entities.forEach(e -> byType.put((String) e.get("entity"), e));
        for (Map<String, Object> e : entities) {
            if (!(e.get("attributes") instanceof List)) continue;     // no model, or property access
            String type = (String) e.get("entity");
            List<Map<String, Object>> attrs = (List<Map<String, Object>>) e.get("attributes");
            double perRequest = num(e.get("perRequest"));

            // useless EAGER: never read, and its target entities are loaded on this endpoint
            for (Map<String, Object> a : attrs) {
                boolean association = "TO_ONE".equals(a.get("kind")) || "TO_MANY".equals(a.get("kind"));
                if (!association || !"EAGER".equals(a.get("fetch")) || num(a.get("readRatio")) > 0 || perRequest == 0) {
                    continue;
                }
                Map<String, Object> target = byType.get((String) a.get("target"));
                double targets = target == null ? 0 : num(target.get("perRequest"));
                if (targets <= 0) continue;
                boolean byDefault = !Boolean.TRUE.equals(a.get("fetchExplicit"));
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("association", simple(type) + "." + a.get("name"));
                ev.put("fetch", byDefault ? "EAGER (JPA default for " + ("TO_ONE".equals(a.get("kind"))
                        ? "@ManyToOne / @OneToOne" : "this mapping") + ")" : "EAGER (explicit)");
                ev.put("readRatio", a.get("readRatio"));
                ev.put("targetEntitiesLoadedPerRequest", targets);
                ev.put("target", a.get("target"));
                out.add(finding(endpoint, "USELESS_EAGER", targets >= 20 ? HIGH : MEDIUM,
                        simple(type) + "." + a.get("name") + " is loaded EAGERly but never read: "
                                + fmt(targets) + " " + simple((String) a.get("target")) + " per request for nothing",
                        ev, "Make it lazy: @" + ("TO_ONE".equals(a.get("kind")) ? "ManyToOne" : "OneToMany")
                                + "(fetch = FetchType.LAZY), and fetch it explicitly where it is needed"));
            }

        }
        // targets of a useless EAGER, with none of their columns read, are its consequence: not reported twice
        java.util.Set<String> eagerConsequences = new java.util.HashSet<>();
        for (Map<String, Object> f : out) {
            if ("USELESS_EAGER".equals(f.get("kind"))) {
                eagerConsequences.add((String) ((Map<String, Object>) f.get("evidence")).get("target"));
            }
        }
        for (Map<String, Object> e : entities) {
            if (!(e.get("attributes") instanceof List)) continue;
            String type = (String) e.get("entity");
            List<Map<String, Object>> attrs = (List<Map<String, Object>>) e.get("attributes");
            double perRequest = num(e.get("perRequest"));

            // over-fetching: large columns never read, or most columns never read on many entities
            if (perRequest <= 0) continue;
            List<String> read = new ArrayList<>();
            List<String> unread = new ArrayList<>();
            List<String> unreadLarge = new ArrayList<>();
            for (Map<String, Object> a : attrs) {
                if (!"BASIC".equals(a.get("kind")) && !"EMBEDDED".equals(a.get("kind"))) continue;
                if (num(a.get("readRatio")) > 0) {
                    read.add((String) a.get("name"));
                } else {
                    unread.add((String) a.get("name"));
                    if (Boolean.TRUE.equals(a.get("large"))) unreadLarge.add((String) a.get("name"));
                }
            }
            int basics = read.size() + unread.size();
            if (read.isEmpty() && eagerConsequences.contains(type)) continue;
            boolean mostUnread = basics > 0 && unread.size() * 2 >= basics && perRequest >= OVER_FETCH_MIN_ENTITIES;
            if (unreadLarge.isEmpty() && !mostUnread) continue;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("entity", type);
            ev.put("loadedPerRequest", perRequest);
            ev.put("columnsRead", read);
            ev.put("columnsNeverRead", unread);
            ev.put("largeColumnsNeverRead", unreadLarge);
            String severity = !unreadLarge.isEmpty() && perRequest >= 100 ? HIGH
                    : (!unreadLarge.isEmpty() || mostUnread) && perRequest >= OVER_FETCH_MIN_ENTITIES ? MEDIUM : LOW;
            out.add(finding(endpoint, "OVER_FETCHING", severity,
                    fmt(perRequest) + " " + simple(type) + " loaded per request, " + read.size() + " of " + basics
                            + " columns read" + (unreadLarge.isEmpty() ? "" : ", large " + unreadLarge + " never read"),
                    ev, "Select only what is used: a DTO or interface projection (select new ...(" + projection(read)
                            + ")) instead of the entity"));
        }

        // ------------------------------------------------------------ in-memory pagination
        if (num(profile.get("inMemoryPaginationRequests")) > 0) {
            Map<String, Object> biggest = statements.stream()
                    .max(Comparator.comparingDouble(s -> num(s.get("rowsPerExecution")))).orElse(null);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("requestsWithInMemoryPagination", profile.get("inMemoryPaginationRequests"));
            ev.put("profiledRequests", profile.get("profiledRequests"));
            ev.put("warning", "HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory");
            if (biggest != null) {
                ev.put("statement", biggest.get("sql"));
                ev.put("rowsReadPerExecution", biggest.get("rowsPerExecution"));
            }
            ev.put("rowsReadPerRequest", profile.get("rowsPerRequest"));
            out.add(finding(endpoint, "IN_MEMORY_PAGINATION", HIGH,
                    "JOIN FETCH + pagination: every matching row is read, then paginated in memory ("
                            + fmt(profile.get("rowsPerRequest")) + " rows read per request)",
                    ev, "Page the root ids first (query with LIMIT), then fetch the collection for those ids "
                            + "(JOIN FETCH ... WHERE id IN :ids), or @EntityGraph + @BatchSize"));
        }

        // ------------------------------------------------------------ open-in-view
        if (num(profile.get("openInViewRequests")) > 0) {
            List<Map<String, Object>> outside = new ArrayList<>();
            for (Map<String, Object> l : lazy) {
                if (num(l.get("outsideTransaction")) > 0) {
                    outside.add(Map.of("association", shortRole((String) l.get("role")),
                            "loadsOutsideTransaction", l.get("outsideTransaction"), "caller", l.get("caller")));
                }
            }
            if (!outside.isEmpty()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("openInViewRequests", profile.get("openInViewRequests"));
                ev.put("lazyLoadsOutsideTransaction", outside);
                out.add(finding(endpoint, "OPEN_IN_VIEW", MEDIUM,
                        "Lazy loading outside any transaction, kept alive by open-in-view (spring.jpa.open-in-view)",
                        ev, "Set spring.jpa.open-in-view=false and load what the response needs inside the "
                                + "@Transactional service (fetch plan or DTO projection)"));
            }
        }

        out.sort(Comparator.comparingInt(f -> SEVERITY_RANK.get((String) f.get("severity"))));
        return out;
    }

    private static Map<String, Object> finding(String endpoint, String kind, String severity, String title,
                                               Map<String, Object> evidence, String fix) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("endpoint", endpoint);
        f.put("kind", kind);
        f.put("severity", severity);
        f.put("title", title);
        f.put("evidence", evidence);
        f.put("fix", fix);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    static String fmt(Object o) {
        double d = num(o);
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    static String simple(String className) {
        return className == null ? "?" : className.substring(className.lastIndexOf('.') + 1);
    }

    /** {@code com.x.domain.Author.books} -> {@code Author.books}. */
    static String shortRole(String role) {
        if (role == null) return "?";
        int last = role.lastIndexOf('.');
        int prev = last > 0 ? role.lastIndexOf('.', last - 1) : -1;
        return role.substring(prev + 1);
    }

    static String attribute(String role) {
        return role == null ? "?" : role.substring(role.lastIndexOf('.') + 1);
    }

    private static String projection(List<String> read) {
        return read.isEmpty() ? "..." : "e." + String.join(", e.", read);
    }
}
