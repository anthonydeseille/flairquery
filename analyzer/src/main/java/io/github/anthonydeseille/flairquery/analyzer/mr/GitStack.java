package io.github.anthonydeseille.flairquery.analyzer.mr;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@link Selection.Stack} on a throw-away copy of the application ({@code <work>/<app>}, so patches taken at the
 * repository root apply as is): {@code git apply --check}, then {@code git apply}. The repository is never touched.
 */
public final class GitStack implements Selection.Stack, AutoCloseable {

    static final Set<String> SKIPPED = Set.of("target", ".git", "build", ".gradle", "node_modules", ".idea");

    private final Path root;

    public GitStack(Path repo, String app, Path work) throws IOException {
        this.root = work;
        delete(work);
        copy(repo.resolve(app), work.resolve(app));
    }

    @Override
    public boolean push(Path patch) throws IOException, InterruptedException {
        if (git("apply", "--check", "--whitespace=nowarn", patch.toAbsolutePath().toString()) != 0) return false;
        return git("apply", "--whitespace=nowarn", patch.toAbsolutePath().toString()) == 0;
    }

    private int git(String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(Stream.concat(Stream.of("git"), Stream.of(args)).toList())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // the copy lives inside the repository (target/...): stop git from discovering it
        pb.environment().put("GIT_CEILING_DIRECTORIES", root.toAbsolutePath().getParent().toString());
        Process p = pb.start();
        if (!p.waitFor(1, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    @Override
    public void close() throws IOException {
        delete(root);
    }

    private static void copy(Path from, Path to) throws IOException {
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

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
