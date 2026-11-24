package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code prove}: proves one or more patches for one finding and writes one report per patch.
 *
 * <pre>
 *   prove --finding ID --patch FILE[,FILE...] [--repo .] [--app demo-app] [--requests FILE]
 *         [--agent-jar agent/target/flairquery-agent.jar] [--base-packages PKG] [--seed corpus:S|none]
 *         [--corpus corpus] [--rounds 5] [--jar target/&lt;app&gt;.jar] [--build "mvnw -B -q package -DskipTests"]
 *         [--ready /actuator/health] [--image postgres:16] [--work target/prover] [--out target/proofs] [--keep true]
 * </pre>
 * Defaults fit this repository's demo application. Exit code 0 when every patch is accepted, 1 otherwise.
 */
public final class ProveCommand {

    public static final String USAGE = """
            usage: prove --finding ID --patch FILE[,FILE...] [--repo .] [--app demo-app] [--requests FILE]
                         [--agent-jar PATH] [--base-packages PKG] [--seed corpus:S|none] [--corpus DIR] [--rounds 5]
                         [--jar target/APP.jar] [--build CMD] [--ready PATH] [--image postgres:16]
                         [--work target/prover] [--out target/proofs] [--keep false]""";

    private ProveCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("finding") || !opt.containsKey("patch")) {
            System.err.println(USAGE);
            return 2;
        }
        Path repo = Path.of(opt.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        String app = opt.getOrDefault("app", "demo-app");
        Path appDir = repo.resolve(app);
        List<Path> patches = Arrays.stream(opt.get("patch").split(",")).map(String::strip)
                .map(p -> Path.of(p).toAbsolutePath().normalize()).toList();
        Path requests = Path.of(opt.getOrDefault("requests", repo.resolve("analyzer/requests-demo.txt").toString()));
        Path agentJar = Path.of(opt.getOrDefault("agent-jar", repo.resolve("agent/target/flairquery-agent.jar").toString()));
        if (!Files.isRegularFile(agentJar)) {
            System.err.println("agent jar not found: " + agentJar + " (make jars, or --agent-jar)");
            return 2;
        }
        String jar = opt.getOrDefault("jar", "target/" + defaultJarName(appDir));
        List<String> build = opt.containsKey("build") ? List.of(opt.get("build").split("\\s+"))
                : List.of(repo.resolve("mvnw").toString(), "-B", "-q", "package", "-DskipTests");
        Path work = Path.of(opt.getOrDefault("work", repo.resolve("target/prover").toString())).toAbsolutePath();
        Path out = Path.of(opt.getOrDefault("out", repo.resolve("target/proofs").toString()));

        Prover.Config config = new Prover.Config(
                new Workspace.Config(repo, app, work, build, jar),
                new AppRun.Options(agentJar, opt.getOrDefault("base-packages", "io.github.anthonydeseille.flairquery.demo"),
                        opt.getOrDefault("ready", "/actuator/health"), Duration.ofMinutes(3)),
                RequestSpec.read(requests),
                Seeder.parse(opt.getOrDefault("seed", "corpus:S"), repo.resolve(opt.getOrDefault("corpus", "corpus"))),
                Integer.parseInt(opt.getOrDefault("rounds", "5")),
                opt.getOrDefault("image", "postgres:16"),
                work.resolve("logs"),
                Judge.Thresholds.DEFAULT,
                Boolean.parseBoolean(opt.getOrDefault("keep", "false")));

        String finding = opt.get("finding");
        List<Verdict> verdicts = new Prover(config, m -> System.out.println("[prove] " + m)).prove(finding, patches);
        write(finding, patches, verdicts, out);
        return verdicts.stream().allMatch(Verdict::accepted) ? 0 : 1;
    }

    static void write(String finding, List<Path> patches, List<Verdict> verdicts, Path out) throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path dir = out.resolve(finding);
        Files.createDirectories(dir);
        ArrayNode index = json.createArrayNode();
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            String name = patches.get(i).getFileName().toString().replaceAll("\\.(patch|diff)$", "");
            json.writeValue(dir.resolve(name + ".json").toFile(), v.report());
            index.addObject().put("patch", patches.get(i).toString()).put("status", v.status().name())
                    .put("report", dir.resolve(name + ".json").toString());
            System.out.printf("%-12s %s%n", v.status(), patches.get(i).getFileName());
            for (String r : v.reasons()) System.out.println("             - " + r);
        }
        json.writeValue(dir.resolve("index.json").toFile(), index);
        System.out.println("reports written to " + dir);
    }

    /** {@code <finalName>.jar} from the application's pom, else {@code <artifactId>-<version>.jar}. */
    static String defaultJarName(Path appDir) throws Exception {
        String pom = Files.readString(appDir.resolve("pom.xml"));
        var finalName = java.util.regex.Pattern.compile("<finalName>([^<]+)</finalName>").matcher(pom);
        if (finalName.find()) return finalName.group(1) + ".jar";
        String noParent = pom.replaceAll("(?s)<parent>.*?</parent>", "").replaceAll("(?s)<dependencies>.*", "");
        var a = java.util.regex.Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(noParent);
        var v = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(noParent);
        if (a.find() && v.find()) return a.group(1) + "-" + v.group(1) + ".jar";
        throw new IllegalArgumentException("cannot guess the jar name from " + appDir.resolve("pom.xml") + ", use --jar");
    }
}
