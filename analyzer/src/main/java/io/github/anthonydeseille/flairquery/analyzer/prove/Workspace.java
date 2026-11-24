package io.github.anthonydeseille.flairquery.analyzer.prove;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies of the application's source tree, one per run (baseline, each candidate), a patch applied on top and
 * the application built. The repository itself is never modified.
 *
 * <p>Layout: {@code <work>/<name>/<app>} where {@code <app>} is the application directory relative to the
 * repository root, so a {@code git diff} taken at the repository root applies as is.</p>
 */
final class Workspace {

    static final Set<String> SKIPPED = Set.of("target", ".git", "build", ".gradle", "node_modules", ".idea");

    /**
     * @param repo  repository root (patch paths are relative to it)
     * @param app   application directory, relative to {@code repo}
     * @param work  where the copies go
     * @param build build command, run in the copied application directory
     * @param jar   built jar, relative to the application directory
     */
    record Config(Path repo, String app, Path work, List<String> build, String jar) { }

    /** Thrown when a candidate cannot be prepared: the patch does not apply or the application does not build. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    /** Database migrations (Liquibase, Flyway) and SQL scripts: they can change plans of unchanged SQL. */
    static final java.util.regex.Pattern MIGRATION = java.util.regex.Pattern.compile(
            "(^|/)(db/changelog|db/migration|migrations?)/|(^|/)changelog[^/]*$|\\.sql$");

    private final Config config;
    private final Map<String, List<String>> patched = new java.util.HashMap<>();

    Workspace(Config config) {
        this.config = config;
    }

    /** Copy of the application ({@code patch} null: unchanged), built; returns the jar. */
    Path prepare(String name, Path patch) throws Failure, IOException, InterruptedException {
        Path root = config.work().resolve(name);
        delete(root);
        Path appCopy = root.resolve(config.app());
        copy(config.repo().resolve(config.app()), appCopy);
        if (patch != null) apply(patch, root);
        Path log = root.resolve("build.log");
        int code = Exec.run(config.build(), appCopy, log, Map.of(), Duration.ofMinutes(15));
        if (code != 0) {
            throw new Failure("does not build (exit " + code + "), see " + log + "\n" + Exec.tail(log, 25));
        }
        Path jar = appCopy.resolve(config.jar());
        if (!Files.isRegularFile(jar)) throw new Failure("built, but no jar at " + jar);
        return jar;
    }

    private void apply(Path patch, Path root) throws Failure, IOException, InterruptedException {
        if (!Files.isRegularFile(patch)) throw new Failure("patch not found: " + patch);
        Path log = root.resolve("patch.log");
        // the copy may live inside the repository (target/...): stop git from discovering it, otherwise
        // `git apply` silently skips the paths outside the current directory
        Map<String, String> env = Map.of("GIT_CEILING_DIRECTORIES", root.toAbsolutePath().getParent().toString());
        Path numstat = root.resolve("patch.files");
        List<String> files = new ArrayList<>();
        int code = Exec.run(List.of("git", "apply", "--numstat", patch.toAbsolutePath().toString()), root, numstat, env,
                Duration.ofMinutes(1));
        if (code != 0) throw new Failure("not a valid patch: " + Exec.tail(numstat, 5));
        for (String line : Files.readAllLines(numstat)) {
            String[] cols = line.split("\t");
            if (cols.length == 3) files.add(cols[2]);
        }
        if (files.isEmpty()) throw new Failure("empty patch");
        String prefix = config.app().replace('\\', '/').replaceAll("/+$", "") + "/";
        for (String f : files) {
            if (!f.startsWith(prefix) || f.contains("..") || f.contains("=>")) {
                throw new Failure("touches " + f + ", outside the application directory " + prefix);
            }
        }
        patched.put(root.getFileName().toString(), List.copyOf(files));
        code = Exec.run(List.of("git", "apply", "--whitespace=nowarn", patch.toAbsolutePath().toString()), root, log, env,
                Duration.ofMinutes(1));
        if (code != 0) throw new Failure("does not apply: " + Exec.tail(log, 10));
    }

    /** Whether the patch applied to run {@code name} touches a database migration or an SQL script. */
    boolean changesSchema(String name) {
        return patched.getOrDefault(name, List.of()).stream().anyMatch(f -> MIGRATION.matcher(f).find());
    }

    /** Deletes the copies and their builds; keeps the logs (application runs) for the reports. */
    void cleanup() throws IOException {
        if (!Files.isDirectory(config.work())) return;
        try (Stream<Path> runs = Files.list(config.work())) {
            for (Path run : runs.toList()) {
                if (!run.getFileName().toString().equals("logs")) delete(run);
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) throw new IOException("application directory not found: " + from);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && SKIPPED.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
