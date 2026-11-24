package io.github.anthonydeseille.flairquery.analyzer.forge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Forge} on the GitHub REST API (github.com or GitHub Enterprise: {@code api} is the API root). The token
 * needs the {@code pull_requests} and {@code contents} write permissions on the repository (a fine-grained token
 * limited to it is enough).
 */
public final class GitHubForge implements Forge {

    static final ObjectMapper JSON = new ObjectMapper();

    private final URI api;
    private final String owner;
    private final String repo;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** @param repository {@code owner/name} */
    public GitHubForge(String api, String repository, String token) {
        String[] parts = repository.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repository must be owner/name: " + repository);
        }
        this.api = URI.create(api.endsWith("/") ? api : api + "/");
        this.owner = parts[0];
        this.repo = parts[1];
        this.token = token;
    }

    @Override
    public List<PullRequest> pulls(String branch) throws IOException, InterruptedException {
        String head = URLEncoder.encode(owner + ":" + branch, StandardCharsets.UTF_8);
        JsonNode all = send("GET", "repos/" + owner + "/" + repo + "/pulls?state=all&per_page=100&head=" + head, null);
        List<PullRequest> out = new ArrayList<>();
        for (JsonNode p : all) out.add(pull(p));
        return out;
    }

    @Override
    public PullRequest create(String branch, String base, String title, String body, boolean draft)
            throws IOException, InterruptedException {
        ObjectNode in = JSON.createObjectNode().put("title", title).put("head", branch).put("base", base)
                .put("body", body).put("draft", draft).put("maintainer_can_modify", true);
        return pull(send("POST", "repos/" + owner + "/" + repo + "/pulls", in));
    }

    @Override
    public void update(int number, String title, String body) throws IOException, InterruptedException {
        send("PATCH", "repos/" + owner + "/" + repo + "/pulls/" + number,
                JSON.createObjectNode().put("title", title).put("body", body));
    }

    @Override
    public void label(int number, List<String> labels) throws IOException, InterruptedException {
        ObjectNode in = JSON.createObjectNode();
        labels.forEach(in.putArray("labels")::add);
        send("POST", "repos/" + owner + "/" + repo + "/issues/" + number + "/labels", in);
    }

    private static PullRequest pull(JsonNode p) {
        return new PullRequest(p.path("number").asInt(), p.path("state").asText(), !p.path("merged_at").isNull()
                && !p.path("merged_at").isMissingNode(), p.path("html_url").asText(), p.path("title").asText(),
                p.path("body").asText(""));
    }

    private JsonNode send(String method, String path, JsonNode body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "flairquery");
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (body != null) b.header("Content-Type", "application/json");
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            String message = r.body();
            try {
                JsonNode e = JSON.readTree(r.body());
                message = e.path("message").asText(message) + (e.has("errors") ? " " + e.path("errors") : "");
            } catch (IOException ignored) {
                // not JSON: keep the raw body
            }
            throw new IOException("GitHub " + method + " " + path.replaceAll("\\?.*", "") + ": HTTP " + r.statusCode()
                    + " " + message);
        }
        return r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
    }
}
