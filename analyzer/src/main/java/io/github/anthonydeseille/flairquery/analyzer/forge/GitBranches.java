package io.github.anthonydeseille.flairquery.analyzer.forge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Branches of a merge request, made with git in a fresh clone of the target repository: each starts from the base
 * branch as it is on the remote, gets the patch applied and one commit, and is pushed. Only branches FlairQuery owns
 * ({@code flairquery/...}) are ever pushed, with {@code --force-with-lease}. The token goes to git through its
 * environment (an HTTP header), never in the URL or the command line.
 */
public final class GitBranches implements AutoCloseable {

    /** A git command's exit code and output. */
    record Out(int code, String text) { }

    private final Path dir;
    private final String base;
    private final Map<String, String> env = new HashMap<>();

    /**
     * @param url   clone URL ({@code https://github.com/owner/repo.git}, or a local path in tests)
     * @param token null for a public or local repository
     */
    public GitBranches(String url, String base, Path dir, String token, String author) throws IOException, InterruptedException {
        this.dir = dir;
        this.base = base;
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (token != null && !token.isBlank() && url.startsWith("https://")) {
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            String host = url.substring(0, url.indexOf('/', "https://".length()) + 1);
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http." + host + ".extraheader");
            env.put("GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic);
        }
        String name = author.replaceAll("\\s*<.*$", "").strip();
        String email = author.contains("<") ? author.replaceAll("^.*<|>.*$", "").strip() : "flairquery@invalid";
        env.put("GIT_AUTHOR_NAME", name);
        env.put("GIT_COMMITTER_NAME", name);
        env.put("GIT_AUTHOR_EMAIL", email);
        env.put("GIT_COMMITTER_EMAIL", email);
        delete(dir);
        Files.createDirectories(dir.getParent());
        Out clone = git(dir.getParent(), "clone", "--quiet", "--branch", base, url, dir.getFileName().toString());
        if (clone.code() != 0) throw new IOException("cannot clone " + url + " (branch " + base + "): " + clone.text());
    }

    /**
     * Branch {@code branch} = {@code origin/<base>} + the patch, one commit.
     *
     * @param strip     leading path components to remove from the patch's paths ({@code git apply -p})
     * @param directory where the application is in the target repository ({@code .} at its root)
     * @return null when ready, else why not (the patch does not apply on the base)
     */
    public String prepare(String branch, Path patch, int strip, String directory, String message)
            throws IOException, InterruptedException {
        check(git(dir, "checkout", "--quiet", "-B", branch, "origin/" + base), "checkout");
        List<String> apply = new ArrayList<>(List.of("apply", "--index", "--whitespace=nowarn", "-p" + strip));
        if (directory != null && !directory.isBlank() && !directory.equals(".")) apply.add("--directory=" + directory);
        apply.add(patch.toAbsolutePath().toString());
        Out applied = git(dir, apply.toArray(String[]::new));
        if (applied.code() != 0) {
            git(dir, "reset", "--quiet", "--hard");
            return "does not apply on " + base + ": " + applied.text().strip();
        }
        check(git(dir, "commit", "--quiet", "--no-verify", "-m", message), "commit");
        return null;
    }

    /** Whether the remote has this branch already with exactly the same content. */
    public boolean sameAsRemote(String branch) throws IOException, InterruptedException {
        Out remote = git(dir, "rev-parse", "--verify", "--quiet", "origin/" + branch + "^{tree}");
        if (remote.code() != 0) return false;
        Out local = git(dir, "rev-parse", branch + "^{tree}");
        return local.text().strip().equals(remote.text().strip());
    }

    public void push(String branch) throws IOException, InterruptedException {
        if (!branch.startsWith("flairquery/")) throw new IllegalArgumentException("not a FlairQuery branch: " + branch);
        check(git(dir, "push", "--quiet", "--force-with-lease", "origin", branch + ":refs/heads/" + branch), "push");
    }

    private void check(Out out, String what) throws IOException {
        if (out.code() != 0) throw new IOException("git " + what + " failed: " + out.text().strip());
    }

    Out git(Path where, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(where.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Out(-1, "timeout");
        }
        return new Out(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        delete(dir);
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.delete(p);
            }
        }
    }
}
