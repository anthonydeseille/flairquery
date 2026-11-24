package io.github.anthonydeseille.flairquery.collector.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.anthonydeseille.flairquery.collector.Collector;
import io.github.anthonydeseille.flairquery.collector.compare.Comparison;
import io.github.anthonydeseille.flairquery.collector.compare.Periods;
import io.github.anthonydeseille.flairquery.collector.infra.InfraSample;
import io.github.anthonydeseille.flairquery.collector.infra.InfraSummary;
import io.github.anthonydeseille.flairquery.collector.log.Log;
import io.github.anthonydeseille.flairquery.collector.store.Store;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * HTTP API of the collector. Everything is read-only except {@code POST /fq/collector/marks}, which writes a
 * mark into fq-store (never into the observed database).
 */
public final class CollectorHttpServer {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_BODY = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Collector collector;
    private final Store store;
    private HttpServer server;

    public CollectorHttpServer(Collector collector, Store store) {
        this.collector = collector;
        this.store = store;
    }

    public int start(int port) throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 16);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "fq-collector-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/fq/collector/queries", ex -> handle(ex, q -> {
            Instant since = since(q);
            int limit = Math.min(500, Math.max(1, Integer.parseInt(q.getOrDefault("limit", "20"))));
            Store.Order order = Store.Order.valueOf(q.getOrDefault("order", "time").toUpperCase(Locale.ROOT));
            return body(q, "queries", store.topStatements(collector.target(), since, limit, order));
        }));
        server.createContext("/fq/collector/endpoints", ex -> handle(ex, q ->
                body(q, "endpoints", store.endpoints(collector.target(), since(q),
                        Math.min(50, Integer.parseInt(q.getOrDefault("statements", "5")))))));
        server.createContext("/fq/collector/plans", ex -> handle(ex, q -> {
            String id = q.get("queryid");
            if (id == null) throw new IllegalArgumentException("queryid is required");
            long queryid;
            try {
                queryid = Long.parseLong(id);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("queryid must be a number");
            }
            return body(q, "plans", store.plans(collector.target(), queryid));
        }));
        server.createContext("/fq/collector/columns", ex -> handle(ex, q ->
                body(q, "columns", store.columnStats(collector.target(), q.get("table")))));
        server.createContext("/fq/collector/marks", this::marks);
        server.createContext("/fq/collector/compare", ex -> handle(ex, q -> {
            List<Store.Mark> marks = store.marks(collector.target());
            Instant now = Instant.now();
            Store.Period before = Periods.resolve(q.get("before"), "before", marks, now);
            Store.Period after = Periods.resolve(q.get("after"), "after", marks, now);
            int limit = Math.min(500, Math.max(1, Integer.parseInt(q.getOrDefault("limit", "20"))));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", SCHEMA_VERSION);
            m.put("target", collector.target());
            m.put("generatedAt", now.toString());
            m.putAll(Comparison.of(store.periodData(collector.target(), before),
                    store.periodData(collector.target(), after), limit));
            return m;
        }));
        server.createContext("/fq/collector/tables", ex -> handle(ex, q ->
                body(q, "tables", store.tableActivity(collector.target(), since(q)))));
        server.createContext("/fq/collector/snapshots", ex -> handle(ex, q ->
                body(q, "snapshots", store.snapshots(collector.target(),
                        Math.min(500, Integer.parseInt(q.getOrDefault("limit", "20")))))));
        server.createContext("/fq/collector/infra", ex -> handle(ex, q -> infra(q)));
        server.createContext("/fq/collector/health", ex -> handle(ex, q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", collector.lastSuccess() != null ? "UP" : "STARTING");
            m.put("lastSuccess", collector.lastSuccess() == null ? null : collector.lastSuccess().toString());
            return m;
        }));
        server.createContext("/metrics", this::metrics);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private interface Handler {
        Object apply(Map<String, String> query) throws Exception;
    }

    private void handle(HttpExchange ex, Handler h) {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Object result;
            int status = 200;
            try {
                result = h.apply(params(ex.getRequestURI()));
            } catch (IllegalArgumentException e) {
                status = 400;
                result = Map.of("error", String.valueOf(e.getMessage()));
            }
            send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(result));
        } catch (Exception e) {
            Log.warn("http request failed", e, "path", ex.getRequestURI().getPath());
        }
    }

    /** GET: list; POST {"name":"before-fix","note":"..."}: runs a cycle now and marks it. */
    private void marks(HttpExchange ex) {
        if (!"POST".equals(ex.getRequestMethod())) {
            handle(ex, q -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Store.Mark m : store.marks(collector.target())) list.add(markJson(m));
                return body(q, "marks", list);
            });
            return;
        }
        try (ex) {
            int status;
            Object result;
            try {
                byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
                if (raw.length > MAX_BODY) throw new IllegalArgumentException("body too large");
                JsonNode req = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
                String name = req.path("name").asText("");
                String note = req.hasNonNull("note") ? req.get("note").asText() : null;
                if (!Periods.MARK_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("name is required: 1-64 characters among A-Z a-z 0-9 . _ -");
                }
                if (note != null && note.length() > 500) throw new IllegalArgumentException("note: 500 characters max");
                if (store.marks(collector.target()).stream().anyMatch(m -> m.name().equals(name))) {
                    status = 409;
                    result = Map.of("error", "mark already exists: " + name);
                } else {
                    Optional<Store.Mark> mark;
                    try {
                        mark = collector.mark(name, note);
                    } catch (Exception e) {
                        Log.warn("mark: collection cycle failed", e, "mark", name);
                        mark = null;
                    }
                    if (mark == null) {
                        status = 503;
                        result = Map.of("error", "collection cycle failed, mark not set (see collector logs)");
                    } else if (mark.isEmpty()) {
                        status = 409;
                        result = Map.of("error", "mark already exists: " + name);
                    } else {
                        status = 201;
                        result = markJson(mark.get());
                    }
                }
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                status = 400;
                result = Map.of("error", String.valueOf(e.getMessage()));
            }
            send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(result));
        } catch (Exception e) {
            Log.warn("http request failed", e, "path", ex.getRequestURI().getPath());
        }
    }

    /** Phase 6: sizing signals over the window (facts, no recommendation). */
    private Map<String, Object> infra(Map<String, String> q) throws Exception {
        Instant since = since(q);
        String t = collector.target();
        Map<String, Object> m = body(q, "containers", InfraSummary.containers(
                store.infraSamples(t, InfraSample.CONTAINER, since), since));
        m.put("jvms", InfraSummary.jvms(store.infraSamples(t, InfraSample.JVM, since)));
        m.put("postgres", InfraSummary.postgres(store.infraSamples(t, InfraSample.POSTGRES, since)));
        Map<String, Object> sources = new LinkedHashMap<>();
        var infra = collector.infra();
        sources.put("kubernetes", infra == null ? -1 : infra.kubernetesUp());
        sources.put("jvmRead", infra == null ? 0 : infra.jvmRead());
        sources.put("jvmFailed", infra == null ? 0 : infra.jvmFailed());
        m.put("sources", sources);
        return m;
    }

    private static Map<String, Object> markJson(Store.Mark m) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", m.name());
        j.put("note", m.note());
        j.put("takenAt", m.takenAt().toString());
        j.put("snapshotId", m.snapshotId());
        return j;
    }

    private Map<String, Object> body(Map<String, String> q, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("target", collector.target());
        m.put("window", q.getOrDefault("window", "15m"));
        m.put("generatedAt", Instant.now().toString());
        m.put(key, value);
        return m;
    }

    private void metrics(HttpExchange ex) {
        try (ex) {
            String t = collector.target().replace("\\", "\\\\").replace("\"", "\\\"");
            StringBuilder sb = new StringBuilder();
            sb.append("# HELP fq_collector_cycles Successful collection cycles\n# TYPE fq_collector_cycles counter\n")
              .append("fq_collector_cycles{target=\"").append(t).append("\"} ").append(collector.cycles()).append('\n')
              .append("# HELP fq_collector_cycle_errors Failed collection cycles\n# TYPE fq_collector_cycle_errors counter\n")
              .append("fq_collector_cycle_errors{target=\"").append(t).append("\"} ").append(collector.errors()).append('\n')
              .append("# HELP fq_collector_last_success_seconds Unix time of the last successful cycle\n")
              .append("# TYPE fq_collector_last_success_seconds gauge\n")
              .append("fq_collector_last_success_seconds{target=\"").append(t).append("\"} ")
              .append(collector.lastSuccess() == null ? 0 : collector.lastSuccess().getEpochSecond()).append('\n')
              .append("# HELP fq_collector_log_lag_bytes Bytes of server log not read yet\n")
              .append("# TYPE fq_collector_log_lag_bytes gauge\n")
              .append("fq_collector_log_lag_bytes{target=\"").append(t).append("\"} ").append(collector.logLag()).append('\n')
              .append("# HELP fq_collector_cycle_duration_seconds Duration of the last successful cycle\n")
              .append("# TYPE fq_collector_cycle_duration_seconds gauge\n")
              .append("fq_collector_cycle_duration_seconds{target=\"").append(t).append("\"} ")
              .append(collector.lastCycleSeconds()).append('\n')
              .append("# HELP fq_collector_column_stats Columns with pg_stats statistics stored\n")
              .append("# TYPE fq_collector_column_stats gauge\n")
              .append("fq_collector_column_stats{target=\"").append(t).append("\"} ").append(collector.columnStats()).append('\n');
            var infra = collector.infra();
            if (infra != null && infra.kubernetesUp() >= 0) {
                sb.append("# HELP fq_collector_kubernetes_up 1 when the Kubernetes API was read on the last cycle\n")
                  .append("# TYPE fq_collector_kubernetes_up gauge\n")
                  .append("fq_collector_kubernetes_up{target=\"").append(t).append("\"} ").append(infra.kubernetesUp()).append('\n');
            }
            if (infra != null) {
                sb.append("# HELP fq_collector_jvm_read Agents whose JVM metrics were read on the last cycle\n")
                  .append("# TYPE fq_collector_jvm_read gauge\n")
                  .append("fq_collector_jvm_read{target=\"").append(t).append("\"} ").append(infra.jvmRead()).append('\n');
            }
            if (collector.agentUp() >= 0) {
                sb.append("# HELP fq_collector_agent_up 1 when the agent endpoints were read on the last cycle\n")
                  .append("# TYPE fq_collector_agent_up gauge\n")
                  .append("fq_collector_agent_up{target=\"").append(t).append("\"} ").append(collector.agentUp()).append('\n');
            }
            send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.warn("metrics failed", e);
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> params(URI uri) {
        Map<String, String> m = new HashMap<>();
        String q = uri.getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) m.put(part.substring(0, eq), java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    /** window=15m|2h|1d|90s (default 15m). */
    static Instant since(Map<String, String> q) {
        return Instant.now().minus(parseWindow(q.getOrDefault("window", "15m")));
    }

    static Duration parseWindow(String w) {
        if (w == null || w.length() < 2) throw new IllegalArgumentException("window: e.g. 15m, 2h, 1d");
        long n;
        try {
            n = Long.parseLong(w.substring(0, w.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("window: e.g. 15m, 2h, 1d");
        }
        return switch (w.charAt(w.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("window: e.g. 15m, 2h, 1d");
        };
    }
}
