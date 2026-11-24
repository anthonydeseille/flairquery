package io.github.anthonydeseille.flairquery.analyzer.forge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code publish}: opens on GitHub the merge requests {@code mr} wrote (dry run first, so they can be read before
 * anything leaves the machine). One branch {@code flairquery/<fix>} and one pull request per selected fix, never
 * merged; see {@link Publisher} for what a second run does.
 */
public final class PublishCommand {

    public static final String USAGE = """
            usage: publish --mr DIR --github OWNER/REPO [--base main] [--app demo-app] [--target-dir .]
                           [--git-url https://github.com/OWNER/REPO.git] [--api https://api.github.com]
                           [--token-env GITHUB_TOKEN] [--draft false] [--labels flairquery]
                           [--author "FlairQuery <flairquery@invalid>"] [--work target/publish]""";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private PublishCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("mr") || !opt.containsKey("github")) {
            System.err.println(USAGE);
            return 2;
        }
        String repository = opt.get("github");
        String token = System.getenv(opt.getOrDefault("token-env", "GITHUB_TOKEN"));
        if (token == null || token.isBlank()) {
            System.err.println(opt.getOrDefault("token-env", "GITHUB_TOKEN") + " is not set: a token with pull request and "
                    + "contents write access to " + repository + " is needed");
            return 2;
        }
        Path mr = Path.of(opt.get("mr"));
        List<Publisher.Request> requests = requests(mr);
        if (requests.isEmpty()) {
            System.out.println("no merge request selected in " + mr.resolve("index.json"));
            return 0;
        }
        String app = opt.getOrDefault("app", "demo-app");
        Publisher.Options options = new Publisher.Options(opt.getOrDefault("base", "main"), strip(app),
                opt.getOrDefault("target-dir", "."), Boolean.parseBoolean(opt.getOrDefault("draft", "false")),
                List.of(opt.getOrDefault("labels", "flairquery").split(",")));
        Forge forge = new GitHubForge(opt.getOrDefault("api", "https://api.github.com"), repository, token);
        String url = opt.getOrDefault("git-url", "https://github.com/" + repository + ".git");
        List<Publisher.Result> results = new ArrayList<>();
        try (GitBranches git = new GitBranches(url, options.base(), Path.of(opt.getOrDefault("work", "target/publish"))
                .toAbsolutePath().resolve("clone"), token, opt.getOrDefault("author", "FlairQuery <flairquery@invalid>"))) {
            Publisher publisher = new Publisher(forge, git, options, System.out::println);
            for (Publisher.Request r : requests) results.add(publisher.publish(r));
        }
        write(results, mr.resolve("published.json"));
        return 0;
    }

    /** The selected merge requests of {@code mr}'s {@code index.json}. */
    public static List<Publisher.Request> requests(Path mr) throws Exception {
        List<Publisher.Request> out = new ArrayList<>();
        for (JsonNode e : JSON.readTree(mr.resolve("index.json").toFile())) {
            if (!e.path("decision").asText().equals("SELECTED")) continue;
            String fix = e.path("fix").asText();
            Path dir = mr.resolve(fix);
            out.add(new Publisher.Request(fix, e.path("branch").asText(), e.path("title").asText(),
                    Files.readString(dir.resolve("description.md")), dir.resolve(fix + ".patch")));
        }
        return out;
    }

    /** {@code git apply -p}: {@code a/} plus the source application directory's components. */
    static int strip(String app) {
        String a = app.replace('\\', '/').replaceAll("^\\./|/+$", "");
        return a.isEmpty() || a.equals(".") ? 1 : 1 + a.split("/").length;
    }

    static void write(List<Publisher.Result> results, Path file) throws Exception {
        ArrayNode all = JSON.createArrayNode();
        for (Publisher.Result r : results) {
            all.addObject().put("fix", r.request().fix()).put("branch", r.request().branch())
                    .put("status", r.status().name()).put("url", r.url()).put("detail", r.detail());
        }
        JSON.writeValue(file.toFile(), all);
    }
}
