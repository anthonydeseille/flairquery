package io.github.anthonydeseille.flairquery.analyzer.size;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** kubectl, always with an explicit context (never the current one). Every command is logged. */
final class Kube {

    record Result(int code, String out, String err) {
        boolean ok() {
            return code == 0;
        }
    }

    private final String kubectl;
    private final String context;
    private final Path log;

    Kube(String kubectl, String context, Path log) {
        this.kubectl = kubectl;
        this.context = context;
        this.log = log;
    }

    String context() {
        return context;
    }

    Result exec(String stdin, Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(kubectl, "--context", context));
        cmd.addAll(List.of(args));
        Path out = Files.createTempFile("fq-kube", ".out");
        Path err = Files.createTempFile("fq-kube", ".err");
        try {
            Process p = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
                logLine(cmd, -1, "timed out after " + timeout.toSeconds() + " s");
                return new Result(-1, Files.readString(out), "timed out after " + timeout.toSeconds() + " s");
            }
            Result r = new Result(p.exitValue(), Files.readString(out), Files.readString(err));
            logLine(cmd, r.code(), r.err());
            return r;
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /** Runs and fails on a non-zero exit. */
    String run(String stdin, Duration timeout, String... args) throws IOException, InterruptedException {
        Result r = exec(stdin, timeout, args);
        if (!r.ok()) {
            throw new IOException("kubectl " + String.join(" ", args) + " failed (" + r.code() + "): " + r.err().strip());
        }
        return r.out();
    }

    /** A port-forward to a service port; closed with {@link PortForward#close()}. */
    PortForward portForward(String namespace, String service, int port) throws IOException {
        List<String> cmd = List.of(kubectl, "--context", context, "-n", namespace, "port-forward", "svc/" + service,
                ":" + port);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Pattern forwarding = Pattern.compile("Forwarding from 127\\.0\\.0\\.1:(\\d+)");
        long deadline = System.currentTimeMillis() + 30_000;
        String line;
        StringBuilder seen = new StringBuilder();
        while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
            Matcher m = forwarding.matcher(line);
            if (m.find()) {
                Thread drain = new Thread(() -> {
                    try {
                        while (r.readLine() != null) { /* keep the pipe empty */ }
                    } catch (IOException ignored) {
                        // process ended
                    }
                }, "fq-port-forward");
                drain.setDaemon(true);
                drain.start();
                return new PortForward(p, Integer.parseInt(m.group(1)));
            }
            seen.append(line).append('\n');
        }
        p.destroyForcibly();
        throw new IOException("port-forward to svc/" + service + " failed: " + seen.toString().strip());
    }

    record PortForward(Process process, int localPort) implements AutoCloseable {
        String url() {
            return "http://127.0.0.1:" + localPort;
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    private void logLine(List<String> cmd, int code, String err) {
        try {
            Files.createDirectories(log.getParent());
            String line = java.time.Instant.now() + " [" + code + "] " + String.join(" ", cmd.subList(3, cmd.size()))
                    + (code != 0 && err != null && !err.isBlank() ? "\n    " + err.strip().replace("\n", "\n    ") : "") + "\n";
            Files.writeString(log, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best effort
        }
    }
}
