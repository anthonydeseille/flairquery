package io.github.anthonydeseille.flairquery.analyzer.forge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Publisher} against a fake GitHub API (an HTTP server on loopback) and a bare git repository standing for
 * the sandbox: the demo app at its root, patches taken in this repository ({@code demo-app/...}).
 */
class PublisherTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path FIXES = Path.of("src/test/resources/fixes").toAbsolutePath();
    static final String BOOK_REPOSITORY = "src/main/java/io/github/anthonydeseille/flairquery/demo/repository/BookRepository.java";
    static final String TOKEN = "test-token";

    @TempDir
    Path tmp;
    Path remote;
    HttpServer server;
    final List<ObjectNode> pulls = Collections.synchronizedList(new ArrayList<>());
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    String api;

    // ------------------------------------------------------------------ fake GitHub

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/repos/acme/sandbox/", this::handle);
        server.start();
        api = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        remote = sandbox();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange x) throws IOException {
        String method = x.getRequestMethod();
        String path = x.getRequestURI().getPath().substring("/api/repos/acme/sandbox/".length());
        calls.add(method + " " + path);
        if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
            reply(x, 401, JSON.createObjectNode().put("message", "Bad credentials"));
            return;
        }
        JsonNode in = method.equals("GET") ? null : JSON.readTree(x.getRequestBody());
        if (method.equals("GET") && path.equals("pulls")) {
            String head = URLDecoder.decode(x.getRequestURI().getRawQuery().replaceAll(".*head=", ""), StandardCharsets.UTF_8);
            ArrayNode out = JSON.createArrayNode();
            for (int i = pulls.size() - 1; i >= 0; i--) {
                if (("acme:" + pulls.get(i).path("head").path("ref").asText()).equals(head)) out.add(pulls.get(i));
            }
            reply(x, 200, out);
        } else if (method.equals("POST") && path.equals("pulls")) {
            if (pulls.stream().anyMatch(p -> p.path("head").path("ref").asText().equals(in.path("head").asText())
                    && p.path("state").asText().equals("open"))) {
                reply(x, 422, JSON.createObjectNode().put("message", "Validation Failed"));
                return;
            }
            int n = pulls.size() + 1;
            ObjectNode p = JSON.createObjectNode().put("number", n).put("state", "open")
                    .put("html_url", "https://github.com/acme/sandbox/pull/" + n).put("title", in.path("title").asText())
                    .put("body", in.path("body").asText()).put("draft", in.path("draft").asBoolean()).putNull("merged_at");
            p.putObject("head").put("ref", in.path("head").asText());
            p.putObject("base").put("ref", in.path("base").asText());
            pulls.add(p);
            reply(x, 201, p);
        } else if (method.equals("PATCH") && path.startsWith("pulls/")) {
            ObjectNode p = pulls.get(Integer.parseInt(path.substring(6)) - 1);
            p.put("title", in.path("title").asText()).put("body", in.path("body").asText());
            reply(x, 200, p);
        } else if (method.equals("POST") && path.matches("issues/\\d+/labels")) {
            ObjectNode p = pulls.get(Integer.parseInt(path.split("/")[1]) - 1);
            p.set("labels", in.path("labels"));
            reply(x, 200, in.path("labels"));
        } else {
            reply(x, 404, JSON.createObjectNode().put("message", "Not Found"));
        }
    }

    static void reply(HttpExchange x, int status, JsonNode body) throws IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    // ------------------------------------------------------------------ sandbox repository

    /** A bare repository whose main branch holds the demo app at its root. */
    Path sandbox() throws Exception {
        Path work = tmp.resolve("seed");
        copy(REPO.resolve("demo-app"), work);
        git(work, "init", "--quiet", "--initial-branch=main");
        git(work, "add", "-A");
        git(work, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "demo app");
        Path bare = tmp.resolve("sandbox.git");
        git(tmp, "clone", "--quiet", "--bare", work.toString(), bare.toString());
        return bare;
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(1, TimeUnit.MINUTES)).isTrue();
        if (p.exitValue() != 0) throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        return out.strip();
    }

    static void copy(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (!dir.equals(from) && dir.getFileName().toString().equals("target")) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    String sha(String branch) throws Exception {
        return git(remote, "rev-parse", "refs/heads/" + branch);
    }

    // ------------------------------------------------------------------ runs

    static final Publisher.Options OPTIONS = new Publisher.Options("main", 2, ".", false, List.of("flairquery"));

    static Publisher.Request request(String fix, String body) {
        return new Publisher.Request("llm-" + fix, "flairquery/llm-" + fix, "FlairQuery: " + fix, body,
                FIXES.resolve(fix + ".patch"));
    }

    List<Publisher.Result> run(Publisher.Request... requests) throws Exception {
        List<Publisher.Result> out = new ArrayList<>();
        try (GitBranches git = new GitBranches(remote.toString(), "main", tmp.resolve("work/clone"), null,
                "FlairQuery <flairquery@invalid>")) {
            Publisher p = new Publisher(new GitHubForge(api, "acme/sandbox", TOKEN), git, OPTIONS, s -> { });
            for (Publisher.Request r : requests) out.add(p.publish(r));
        }
        return out;
    }

    static final String TRAP1 = "trap1-authors-list-title-projection";
    static final String TRAP3 = "trap3-book-titles-dto-projection";

    @Test
    void aFirstRunPushesOneBranchAndOpensOneLabelledPullRequestPerFix() throws Exception {
        List<Publisher.Result> r = run(request(TRAP1, "body 1"), request(TRAP3, "body 3"));

        assertThat(r).extracting(Publisher.Result::status).containsExactly(Publisher.Status.CREATED, Publisher.Status.CREATED);
        assertThat(r.get(0).url()).isEqualTo("https://github.com/acme/sandbox/pull/1");
        assertThat(pulls.get(1).path("base").path("ref").asText()).isEqualTo("main");
        assertThat(pulls.get(1).path("labels").get(0).asText()).isEqualTo("flairquery");
        // each branch = main + its own patch, the demo's demo-app/ prefix stripped (the app is at the sandbox's root)
        assertThat(git(remote, "show", "flairquery/llm-" + TRAP3 + ":" + BOOK_REPOSITORY)).contains("findTitlesByAuthorId")
                .doesNotContain("findTitlesByAuthorIds");
        assertThat(git(remote, "rev-parse", "flairquery/llm-" + TRAP3 + "^")).isEqualTo(sha("main"));
        assertThat(git(remote, "log", "-1", "--format=%an <%ae>%n%s", "flairquery/llm-" + TRAP1))
                .isEqualTo("FlairQuery <flairquery@invalid>\nFlairQuery: " + TRAP1);
        assertThat(git(remote, "show", "main:" + BOOK_REPOSITORY)).doesNotContain("findTitlesByAuthorId");
    }

    @Test
    void aSecondRunChangesNothingThenOnlyWhatChanged() throws Exception {
        run(request(TRAP1, "body 1"));
        String pushed = sha("flairquery/llm-" + TRAP1);
        calls.clear();

        assertThat(run(request(TRAP1, "body 1"))).extracting(Publisher.Result::status).containsExactly(Publisher.Status.UNCHANGED);
        assertThat(calls).containsExactly("GET pulls");
        assertThat(sha("flairquery/llm-" + TRAP1)).isEqualTo(pushed);

        Publisher.Result updated = run(request(TRAP1, "body 1, proven again")).get(0);
        assertThat(updated.status()).isEqualTo(Publisher.Status.UPDATED);
        assertThat(updated.detail()).isEqualTo("#1 description updated");
        assertThat(pulls.get(0).path("body").asText()).isEqualTo("body 1, proven again");
        assertThat(sha("flairquery/llm-" + TRAP1)).isEqualTo(pushed);
        assertThat(pulls).hasSize(1);
    }

    @Test
    void aClosedOrMergedPullRequestIsLeftAlone() throws Exception {
        run(request(TRAP1, "b"), request(TRAP3, "b"));
        String pushed = sha("flairquery/llm-" + TRAP1);
        pulls.get(0).put("state", "closed");
        pulls.get(1).put("state", "closed").put("merged_at", "2026-11-24T20:00:00Z");

        List<Publisher.Result> r = run(request(TRAP1, "new body"), request(TRAP3, "new body"));

        assertThat(r).extracting(Publisher.Result::status).containsExactly(Publisher.Status.CLOSED, Publisher.Status.MERGED);
        assertThat(pulls).hasSize(2);
        assertThat(pulls.get(0).path("body").asText()).isEqualTo("b");
        assertThat(sha("flairquery/llm-" + TRAP1)).isEqualTo(pushed);
    }

    @Test
    void aFixThatNoLongerAppliesOnTheBaseIsSkippedWithTheReason() throws Exception {
        // main moves: trap 1's fix is merged; trap 3's changes the same lines of BookRepository
        Path work = tmp.resolve("dev");
        git(tmp, "clone", "--quiet", remote.toString(), work.toString());
        git(work, "apply", "-p2", FIXES.resolve(TRAP1 + ".patch").toString());
        git(work, "add", "-A");
        git(work, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "trap 1 merged");
        git(work, "push", "--quiet", "origin", "main");

        Publisher.Result r = run(request(TRAP3, "b")).get(0);

        assertThat(r.status()).isEqualTo(Publisher.Status.NOT_APPLIED);
        assertThat(r.detail()).startsWith("does not apply on main: ").contains("BookRepository.java");
        assertThat(pulls).isEmpty();
        assertThat(git(remote, "branch", "--list", "flairquery/*")).isEmpty();
    }

    @Test
    void theRequestsOfADryRunAreReadAndForgeErrorsAreExplicit() throws Exception {
        Path mr = tmp.resolve("mr");
        Files.createDirectories(mr.resolve("a"));
        Files.writeString(mr.resolve("a/description.md"), "the body");
        Files.writeString(mr.resolve("index.json"), """
                [ {"fix": "a", "decision": "SELECTED", "branch": "flairquery/a", "title": "t"},
                  {"fix": "b", "decision": "DEFERRED"} ]""");
        assertThat(PublishCommand.requests(mr)).containsExactly(
                new Publisher.Request("a", "flairquery/a", "t", "the body", mr.resolve("a/a.patch")));
        assertThat(PublishCommand.strip("demo-app")).isEqualTo(2);
        assertThat(PublishCommand.strip(".")).isEqualTo(1);
        assertThat(PublishCommand.strip("apps/shop/")).isEqualTo(3);

        assertThatThrownBy(() -> new GitHubForge(api, "acme/sandbox", "wrong").pulls("x"))
                .hasMessage("GitHub GET repos/acme/sandbox/pulls: HTTP 401 Bad credentials");
        assertThatThrownBy(() -> new GitHubForge(api, "acme", TOKEN)).hasMessageContaining("owner/name");
    }
}
