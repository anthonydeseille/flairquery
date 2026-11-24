package io.github.anthonydeseille.flairquery.agent.metrics;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;

import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Prometheus text exposition format 0.0.4, written by hand (no client library). */
public final class PrometheusWriter {

    private PrometheusWriter() { }

    public static String write(Registry r) {
        return write(r, null);
    }

    /** @param jvm a {@link JvmMetrics#snapshot()}, or null to leave the JVM families out */
    public static String write(Registry r, Map<String, Object> jvm) {
        StringBuilder sb = new StringBuilder(4096);
        Map<String, Registry.EndpointStats> eps = r.endpointStats();

        family(sb, r, eps, "fq_queries_count", "counter",
                "SQL statements executed, by endpoint", e -> e.queries.sum());
        family(sb, r, eps, "fq_queries_time_seconds", "counter",
                "Total SQL execution time (JDBC execute calls), by endpoint", e -> e.sqlNanos.sum() / 1e9);
        family(sb, r, eps, "fq_nplusone", "counter",
                "HTTP requests in which an N+1 pattern was detected, by endpoint", e -> e.nPlusOneRequests.sum());
        family(sb, r, eps, "fq_http_requests_count", "counter",
                "HTTP requests observed (sampled), by endpoint", e -> e.requests.sum());
        family(sb, r, eps, "fq_http_time_seconds", "counter",
                "Total HTTP request time (sampled), by endpoint", e -> e.httpNanos.sum() / 1e9);

        sb.append("# HELP fq_agent_errors Internal agent errors (never propagated to the application)\n")
          .append("# TYPE fq_agent_errors counter\n")
          .append("fq_agent_errors{app=\"").append(escape(r.app())).append("\"} ").append(Fq.errorCount()).append('\n');
        sb.append("# HELP fq_dropped Entries dropped because an in-memory bound was reached\n")
          .append("# TYPE fq_dropped counter\n")
          .append("fq_dropped{app=\"").append(escape(r.app())).append("\"} ").append(r.dropped()).append('\n');
        sb.append("# HELP fq_agent_active 1 when instrumentation is active, 0 after the circuit breaker opened\n")
          .append("# TYPE fq_agent_active gauge\n")
          .append("fq_agent_active{app=\"").append(escape(r.app())).append("\"} ").append(Fq.isActive() ? 1 : 0).append('\n');
        if (jvm != null) {
            jvm(sb, escape(r.app()), jvm);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void jvm(StringBuilder sb, String app, Map<String, Object> jvm) {
        String base = "app=\"" + app + "\"";
        java.util.List<String[]> lines = new java.util.ArrayList<>();
        if (jvm.get("heap") instanceof Map<?, ?> h) {
            lines.add(sample("fq_jvm_memory_used_bytes", base + ",area=\"heap\"", ((Map<String, Object>) h).get("usedBytes")));
            lines.add(sample("fq_jvm_memory_max_bytes", base + ",area=\"heap\"", ((Map<String, Object>) h).get("maxBytes")));
        }
        if (jvm.get("nonHeap") instanceof Map<?, ?> h) {
            lines.add(sample("fq_jvm_memory_used_bytes", base + ",area=\"nonheap\"", ((Map<String, Object>) h).get("usedBytes")));
        }
        if (jvm.get("runtime") instanceof Map<?, ?> rt) {
            lines.add(sample("fq_jvm_rss_bytes", base, ((Map<String, Object>) rt).get("rssBytes")));
            lines.add(sample("fq_jvm_container_memory_bytes", base, ((Map<String, Object>) rt).get("containerMemoryBytes")));
        }
        if (jvm.get("gc") instanceof java.util.List<?> gcs) {
            for (Object o : gcs) {
                Map<String, Object> g = (Map<String, Object>) o;
                String l = base + ",gc=\"" + escape(String.valueOf(g.get("name"))) + "\"";
                lines.add(sample("fq_jvm_gc_collections", l, g.get("count")));
                Object ms = g.get("timeMs");
                lines.add(sample("fq_jvm_gc_time_seconds", l, ms instanceof Number n ? n.doubleValue() / 1000 : null));
            }
        }
        if (jvm.get("threads") instanceof Map<?, ?> t) {
            lines.add(sample("fq_jvm_threads", base + ",state=\"live\"", ((Map<String, Object>) t).get("live")));
            lines.add(sample("fq_jvm_threads", base + ",state=\"peak\"", ((Map<String, Object>) t).get("peak")));
        }
        if (jvm.get("hikari") instanceof java.util.List<?> pools) {
            for (Object o : pools) {
                Map<String, Object> p = (Map<String, Object>) o;
                String l = base + ",pool=\"" + escape(String.valueOf(p.get("pool"))) + "\"";
                for (String state : new String[]{"active", "idle", "total", "waiting", "maximumPoolSize", "peakActive", "peakWaiting"}) {
                    lines.add(sample("fq_hikari_connections", l + ",state=\"" + state + "\"", p.get(state)));
                }
            }
        }
        lines.removeIf(java.util.Objects::isNull);
        lines.sort(java.util.Comparator.comparing(l -> l[0]));   // stable: a family's samples stay together, in order
        String current = null;
        for (String[] line : lines) {
            if (!line[0].equals(current)) {
                current = line[0];
                sb.append("# TYPE ").append(current).append(' ')
                  .append(current.endsWith("_collections") || current.endsWith("_seconds") ? "counter" : "gauge").append('\n');
            }
            sb.append(line[0]).append('{').append(line[1]).append("} ").append(line[2]).append('\n');
        }
    }

    private static String[] sample(String name, String labels, Object value) {
        if (!(value instanceof Number n)) return null;
        double v = n.doubleValue();
        return new String[]{name, labels, v == Math.rint(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v)};
    }

    private static void family(StringBuilder sb, Registry r, Map<String, Registry.EndpointStats> eps, String name,
                               String type, String help, ToDoubleFunction<Registry.EndpointStats> value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (Registry.EndpointStats e : eps.values()) {
            sb.append(name).append("{app=\"").append(escape(r.app()))
              .append("\",endpoint=\"").append(escape(e.name)).append("\"} ");
            double v = value.applyAsDouble(e);
            if (v == Math.rint(v) && !Double.isInfinite(v)) {
                sb.append((long) v);
            } else {
                sb.append(v);
            }
            sb.append('\n');
        }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
