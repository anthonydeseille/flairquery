package io.github.anthonydeseille.flairquery.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.anthonydeseille.flairquery.agent.log.Json;
import io.github.anthonydeseille.flairquery.agent.log.JsonLog;
import io.github.anthonydeseille.flairquery.agent.metrics.PrometheusWriter;
import io.github.anthonydeseille.flairquery.agent.metrics.Registry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only HTTP endpoints on {@code FQ_HTTP_PORT}, served by the JDK HttpServer on one daemon thread:
 * {@code /metrics}, {@code /fq/top}, {@code /fq/nplusone}, {@code /fq/endpoints}, {@code /fq/health}.
 */
public final class FqHttpServer {

    public static final int SCHEMA_VERSION = 1;
    static final int DEFAULT_TOP = 20;
    static final int MAX_TOP = 500;

    private final Registry registry;
    private HttpServer server;
    private ExecutorService executor;

    public FqHttpServer(Registry registry) {
        this.registry = registry;
    }

    /** Never throws: a busy port or a JRE without jdk.httpserver only disables the endpoints. */
    public boolean start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 16);
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "flairquery-http");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.createContext("/metrics", ex -> respond(ex, 200, "text/plain; version=0.0.4; charset=utf-8",
                    PrometheusWriter.write(registry)));
            server.createContext("/fq/top", ex -> json(ex, Map.of("top", registry.top(limit(ex.getRequestURI())))));
            server.createContext("/fq/nplusone", ex -> json(ex, Map.of(
                    "threshold", registry.nPlusOneThreshold(), "nplusone", registry.nPlusOne())));
            server.createContext("/fq/endpoints", ex -> json(ex, Map.of("endpoints", registry.endpoints())));
            server.createContext("/fq/health", ex -> json(ex, Map.of("status", "UP")));
            server.createContext("/", ex -> respond(ex, 404, "application/json", "{\"error\":\"not found\"}"));
            startAsDaemon(server);
            JsonLog.info("http server started", "port", port());
            return true;
        } catch (Throwable t) {
            JsonLog.warn("http server not started, metrics endpoints unavailable (application unaffected)", t,
                    "port", port);
            stop();
            return false;
        }
    }

    /**
     * {@code HttpServer#start} creates its "HTTP-Dispatcher" thread with the daemon status of the calling
     * thread. Started from the premain (non-daemon) thread, it would keep the JVM alive after the
     * application ends (e.g. a failed Spring startup never exits). Starting it from a daemon thread
     * guarantees the agent never prevents JVM shutdown.
     */
    private static void startAsDaemon(HttpServer server) throws InterruptedException {
        Throwable[] failure = new Throwable[1];
        Thread starter = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "flairquery-http-starter");
        starter.setDaemon(true);
        starter.start();
        starter.join(10_000);
        if (failure[0] != null) {
            throw new IllegalStateException("http server start failed", failure[0]);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        try {
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private void json(HttpExchange ex, Map<String, Object> body) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("app", registry.app());
        m.put("generatedAt", Instant.now().toString());
        m.putAll(body);
        respond(ex, 200, "application/json; charset=utf-8", Json.toJson(m));
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Throwable t) {
            JsonLog.debug("http response failed", "error", String.valueOf(t));
        }
    }

    static int limit(URI uri) {
        String q = uri.getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                if (part.startsWith("limit=")) {
                    try {
                        return Math.max(1, Math.min(MAX_TOP, Integer.parseInt(part.substring(6))));
                    } catch (NumberFormatException ignored) {
                        // default
                    }
                }
            }
        }
        return DEFAULT_TOP;
    }
}
