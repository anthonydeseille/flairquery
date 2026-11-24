package io.github.anthonydeseille.flairquery.analyzer.prove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a command to completion, output to a log file. */
final class Exec {

    private Exec() { }

    /** @return the exit code, -1 on timeout (the process is killed) */
    static int run(List<String> cmd, Path dir, Path log, Map<String, String> env, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            return -1;
        }
        return p.exitValue();
    }

    /** Last lines of a log file (for error messages). */
    static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }
}
