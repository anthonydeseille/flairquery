package io.github.anthonydeseille.flairquery.analyzer.mr;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@link Selection.Stack} on a throw-away copy of the application ({@code <work>/<app>}, so patches taken at the
 * repository root apply as is): {@code git apply --check}, then {@code git apply}. The repository is never touched.
 */
public final class GitStack implements Selection.Stack, AutoCloseable {

    private final Path root;

    public GitStack(Path repo, String app, Path work) throws IOException {
        this.root = work;
        Tree.delete(work);
        Tree.copy(repo.resolve(app), work.resolve(app));
    }

    @Override
    public boolean push(Path patch) throws IOException, InterruptedException {
        String file = patch.toAbsolutePath().toString();
        if (git("apply", "--check", "--whitespace=nowarn", file) != 0) return false;
        return git("apply", "--whitespace=nowarn", file) == 0;
    }

    private int git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return Tree.run(cmd, root, null, root.toAbsolutePath().getParent(), Duration.ofMinutes(1));
    }

    @Override
    public void close() throws IOException {
        Tree.delete(root);
    }
}
