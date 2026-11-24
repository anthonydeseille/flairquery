package io.github.anthonydeseille.flairquery.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder;
import io.github.anthonydeseille.flairquery.analyzer.code.SourceLocator;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;
import io.github.anthonydeseille.flairquery.analyzer.prove.ProveCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FlairQuery analyzer (phase 4).
 *
 * <pre>
 *   bundle --agent URL --collector URL --source DIR --out DIR [--window 1h]
 *   prove  --finding ID --patch FILE[,FILE...] [options, see {@link ProveCommand}]
 * </pre>
 * {@code bundle} writes one evidence bundle per finding ({@code <id>.json}) and an {@code index.json};
 * {@code prove} proves patches for one finding (accepted / rejected, one report per patch).
 */
public final class AnalyzerMain {

    private AnalyzerMain() { }

    public static void main(String[] args) throws Exception {
        // Testcontainers / docker-java log through SLF4J: warnings only
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn"));
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("prove")) {
            return ProveCommand.run(options(args));
        }
        if (args.length == 0 || !args[0].equals("bundle")) {
            System.err.println("usage: bundle --agent URL --collector URL --source DIR --out DIR [--window 1h]");
            System.err.println("       " + ProveCommand.USAGE.substring("usage: ".length()));
            return 2;
        }
        Map<String, String> opt = options(args);
        if (!opt.containsKey("source") || !opt.containsKey("out")
                || (!opt.containsKey("agent") && !opt.containsKey("collector"))) {
            System.err.println("bundle needs --source, --out and --agent and/or --collector");
            return 2;
        }
        JsonSource agent = opt.containsKey("agent") ? JsonSource.http(opt.get("agent")) : null;
        JsonSource collector = opt.containsKey("collector") ? JsonSource.http(opt.get("collector")) : null;
        SourceLocator sources = new SourceLocator(Path.of(opt.get("source")));
        List<ObjectNode> bundles = new BundleBuilder(agent, collector, sources, opt.getOrDefault("window", "1h")).build();
        write(bundles, Path.of(opt.get("out")));
        return 0;
    }

    static void write(List<ObjectNode> bundles, Path out) throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(out);
        ArrayNode index = json.createArrayNode();
        for (ObjectNode b : bundles) {
            json.writeValue(out.resolve(b.path("id").asText() + ".json").toFile(), b);
            ObjectNode i = index.addObject();
            for (String k : List.of("id", "kind", "severity", "endpoint", "title", "origin")) i.set(k, b.get(k));
            i.put("sources", b.path("sources").size());
            System.out.printf("%-8s %-22s %-26s %-40s %d source file(s)%n", b.path("severity").asText(),
                    b.path("kind").asText(), b.path("endpoint").asText(), b.path("id").asText(), b.path("sources").size());
        }
        json.writeValue(out.resolve("index.json").toFile(), index);
        System.out.println(bundles.size() + " bundle(s) written to " + out);
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + args[i]);
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }
}
