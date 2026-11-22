package io.github.anthonydeseille.flairquery.agent.metrics;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;

import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Prometheus text exposition format 0.0.4, written by hand (no client library). */
public final class PrometheusWriter {

    private PrometheusWriter() { }

    public static String write(Registry r) {
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
        return sb.toString();
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
