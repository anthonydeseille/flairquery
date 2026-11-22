package io.github.anthonydeseille.flairquery.it;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The demo app launched in its own JVM (optionally with -javaagent), as in production. */
final class DemoAppProcess implements AutoCloseable {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    final String name;
    final int port;
    final int agentPort;
    final Path log;
    private final Process process;

    private DemoAppProcess(String name, int port, int agentPort, Path log, Process process) {
        this.name = name;
        this.port = port;
        this.agentPort = agentPort;
        this.log = log;
        this.process = process;
    }

    static Path agentJar() {
        return requireFile(System.getProperty("fq.agent.jar"), "agent jar (run ./mvnw verify from the root)");
    }

    static Path demoJar() {
        return requireFile(System.getProperty("fq.demo.jar"), "demo app jar (run ./mvnw verify from the root)");
    }

    /** Starts the app and waits until it is ready. */
    static DemoAppProcess start(String name, boolean withAgent, Map<String, String> env) throws Exception {
        DemoAppProcess app = launch(name, withAgent, env, ItEnvironment.jdbcUrl());
        app.awaitReady(Duration.ofSeconds(120));
        return app;
    }

    /** Starts the app without waiting (used to observe failures). */
    static DemoAppProcess launch(String name, boolean withAgent, Map<String, String> env, String jdbcUrl)
            throws IOException {
        int port = freePort();
        int agentPort = freePort();
        Path logs = Path.of(System.getProperty("fq.it.logs", "target/it-logs"));
        Files.createDirectories(logs);
        Path log = logs.resolve(name + ".log");

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx512m");
        if (withAgent) {
            cmd.add("-javaagent:" + agentJar());
        }
        cmd.add("-jar");
        cmd.add(demoJar().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> e = pb.environment();
        e.keySet().removeIf(k -> k.startsWith("FQ_") || k.equals("JAVA_TOOL_OPTIONS"));
        e.put("DB_URL", jdbcUrl);
        e.put("DB_USER", "flairquery");
        e.put("DB_PASSWORD", "flairquery");
        e.put("SERVER_PORT", String.valueOf(port));
        e.put("FQ_HTTP_PORT", String.valueOf(agentPort));
        e.put("FQ_APP_NAME", "it-" + name);
        e.put("FQ_BASE_PACKAGES", "io.github.anthonydeseille.flairquery.demo");
        e.putAll(env);
        return new DemoAppProcess(name, port, agentPort, log, pb.start());
    }

    void awaitReady(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("demo app '" + name + "' died at startup, see " + log + "\n" + logTail());
            }
            try {
                if (get("/actuator/health/readiness").statusCode() == 200) {
                    return;
                }
            } catch (ConnectException | java.net.http.HttpConnectTimeoutException ignored) {
                // not yet listening
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("demo app '" + name + "' not ready after " + timeout + "\n" + logTail());
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String getOk(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = get(path);
        if (r.statusCode() != 200) {
            throw new AssertionError(path + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    HttpResponse<String> agent(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + agentPort + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode agentJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = agent(path);
        if (r.statusCode() != 200) {
            throw new AssertionError("agent " + path + " -> HTTP " + r.statusCode());
        }
        return ItEnvironment.JSON.readTree(r.body());
    }

    boolean waitForExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    String logText() throws IOException {
        return Files.readString(log);
    }

    String logTail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Path requireFile(String path, String what) {
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw new IllegalStateException("missing " + what + ": " + path);
        }
        return Path.of(path);
    }
}
