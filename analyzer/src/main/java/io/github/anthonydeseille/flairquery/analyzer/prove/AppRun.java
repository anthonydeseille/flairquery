package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The application (a Spring Boot jar) in its own JVM, optionally with the FlairQuery agent, on one database.
 * The datasource and the port are set through Spring Boot's standard environment variables
 * ({@code SPRING_DATASOURCE_URL}, {@code SERVER_PORT}...), which override the application's configuration.
 */
final class AppRun implements AutoCloseable {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param agentJar     null: without the agent
     * @param basePackages FQ_BASE_PACKAGES of the agent
     * @param readyPath    returns 200 once the application is ready
     */
    record Options(Path agentJar, String basePackages, String readyPath, Duration startTimeout) { }

    final String name;
    final int port;
    final int agentPort;
    final Path log;
    private final Process process;

    private AppRun(String name, int port, int agentPort, Path log, Process process) {
        this.name = name;
        this.port = port;
        this.agentPort = agentPort;
        this.log = log;
        this.process = process;
    }

    /**
     * Starts the application and waits until it is ready; fails with the end of its log otherwise. Retried once
     * when a port picked as free was taken in the meantime.
     */
    static AppRun start(String name, Path jar, boolean withAgent, Options o, String jdbcUrl, String user,
                        String password, Path logDir) throws IOException, InterruptedException {
        try {
            return startOnce(name, jar, withAgent, o, jdbcUrl, user, password, logDir);
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).contains("already in use")) throw e;
            return startOnce(name, jar, withAgent, o, jdbcUrl, user, password, logDir);
        }
    }

    private static AppRun startOnce(String name, Path jar, boolean withAgent, Options o, String jdbcUrl, String user,
                                    String password, Path logDir) throws IOException, InterruptedException {
        int[] ports = freePorts(2);
        int port = ports[0];
        int agentPort = ports[1];
        Files.createDirectories(logDir);
        Path log = logDir.resolve(name + ".log");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx512m");
        if (withAgent) cmd.add("-javaagent:" + o.agentJar().toAbsolutePath());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("FQ_") || k.startsWith("SPRING_") || k.equals("JAVA_TOOL_OPTIONS"));
        env.put("SPRING_DATASOURCE_URL", jdbcUrl);
        env.put("SPRING_DATASOURCE_USERNAME", user);
        env.put("SPRING_DATASOURCE_PASSWORD", password);
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("FQ_HTTP_PORT", String.valueOf(agentPort));
        env.put("FQ_APP_NAME", "prover-" + name);
        env.put("FQ_PROFILE_SAMPLE", "1");
        if (o.basePackages() != null) env.put("FQ_BASE_PACKAGES", o.basePackages());
        AppRun app = new AppRun(name, port, agentPort, log, pb.start());
        try {
            app.awaitReady(o.readyPath(), o.startTimeout());
        } catch (IOException | InterruptedException | RuntimeException e) {
            app.close();
            throw e;
        }
        return app;
    }

    private void awaitReady(String readyPath, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("'" + name + "' died at startup, see " + log + "\n" + Exec.tail(log, 30));
            }
            try {
                if (get(readyPath).statusCode() == 200) return;
            } catch (ConnectException | HttpConnectTimeoutException ignored) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("'" + name + "' not ready after " + timeout + "\n" + Exec.tail(log, 30));
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode agent(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + agentPort + path))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("agent " + path + " -> HTTP " + r.statusCode());
        return JSON.readTree(r.body());
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Distinct free ports: all sockets are held open together, so the same port is never returned twice. Each
     * port is also bound on the loopback address: on macOS a wildcard bind succeeds on a port another process
     * (colima / Docker port forwarding) holds on 127.0.0.1, and the application would then fail to start.
     */
    static int[] freePorts(int n) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            int[] ports = new int[n];
            int found = 0;
            for (int attempt = 0; found < n && attempt < 50; attempt++) {
                ServerSocket any = new ServerSocket(0);
                sockets.add(any);
                try {
                    sockets.add(new ServerSocket(any.getLocalPort(), 50, InetAddress.getLoopbackAddress()));
                } catch (IOException taken) {
                    continue;
                }
                ports[found++] = any.getLocalPort();
            }
            if (found < n) throw new IOException("no free port found");
            return ports;
        } finally {
            for (ServerSocket s : sockets) s.close();
        }
    }
}
