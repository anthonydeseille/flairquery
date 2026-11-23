package io.github.anthonydeseille.flairquery.collector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.anthonydeseille.flairquery.collector.Collector;
import io.github.anthonydeseille.flairquery.collector.log.Log;
import io.github.anthonydeseille.flairquery.collector.store.Store;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Read-only HTTP API of the collector:
 * {@code /fq/collector/queries}, {@code /fq/collector/tables}, {@code /fq/collector/snapshots},
 * {@code /fq/collector/health}, {@code /metrics}.
 */
public final class CollectorHttpServer {

    public static final int SCHEMA_VERSION = 1;
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
        server.createContext("/fq/collector/tables", ex -> handle(ex, q ->
                body(q, "tables", store.tableActivity(collector.target(), since(q)))));
        server.createContext("/fq/collector/snapshots", ex -> handle(ex, q ->
                body(q, "snapshots", store.snapshots(collector.target(),
                        Math.min(500, Integer.parseInt(q.getOrDefault("limit", "20")))))));
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
              .append(collector.lastSuccess() == null ? 0 : collector.lastSuccess().getEpochSecond()).append('\n');
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
