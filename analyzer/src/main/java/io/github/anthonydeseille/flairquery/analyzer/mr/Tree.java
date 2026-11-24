package io.github.anthonydeseille.flairquery.analyzer.mr;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Throw-away copies of the application's source tree, and the commands run in them. */
final class Tree {

    static final Set<String> SKIPPED = Set.of("target", ".git", "build", ".gradle", "node_modules", ".idea");

    private Tree() { }

    /** Copies a source tree, without build output, VCS or IDE folders. */
    static void copy(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && SKIPPED.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /**
     * Runs a command, output appended to {@code log} (or discarded when null).
     *
     * @param ceiling git does not look for a repository above it (copies live inside the repository's target/)
     * @return the exit code, -1 on timeout
     */
    static int run(List<String> cmd, Path dir, Path log, Path ceiling, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        if (log == null) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        } else {
            Files.createDirectories(log.getParent());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        }
        if (ceiling != null) pb.environment().putAll(Map.of("GIT_CEILING_DIRECTORIES", ceiling.toAbsolutePath().toString()));
        Process p = pb.start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            return -1;
        }
        return p.exitValue();
    }
}
