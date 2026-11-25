package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code size}: deterministic infrastructure sizing findings (phase 6) from the collector's measurements.
 * Writes {@code findings.json}, the raw {@code infra.json} it used, and {@code scorecard.md}.
 */
public final class SizeCommand {

    public static final String USAGE = "usage: size --collector URL --out DIR [--window 15m] [--postgres-workload Deployment/postgres]";

    private SizeCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("collector") || !opt.containsKey("out")) {
            System.err.println(USAGE);
            return 2;
        }
        String window = opt.getOrDefault("window", "15m");
        JsonSource collector = JsonSource.http(opt.get("collector"));
        JsonNode infra = collector.get("/fq/collector/infra?window=" + window);
        JsonNode temp = collector.get("/fq/collector/queries?order=temp&limit=10&window=" + window);
        return write(infra, temp, window, opt.get("postgres-workload"), Path.of(opt.get("out")));
    }

    static int write(JsonNode infra, JsonNode temp, String window, String postgresWorkload, Path out) throws Exception {
        List<SizingFinding> findings = new Sizer(window, postgresWorkload).size(infra, temp);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(out);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("window", window);
        doc.put("generatedAt", infra.path("generatedAt").asText(null));
        doc.put("sources", infra.path("sources"));
        doc.put("findings", findings);
        json.writeValue(out.resolve("findings.json").toFile(), doc);
        json.writeValue(out.resolve("infra.json").toFile(), infra);

        for (SizingFinding f : findings) {
            System.out.printf("%-6s %-24s %-40s %s%n", f.severity(), f.rule(), f.target(), f.title());
            System.out.printf("       now %s -> proposed %s%n", f.current(), f.recommended());
        }
        if (infra.path("sources").path("kubernetes").asInt(-1) != 1) {
            System.out.println("note: Kubernetes not read by the collector: container rules (I1-I3, I6 replicas) incomplete");
        }
        List<InfraScorecard.Row> rows = InfraScorecard.of(findings, infra);
        String md = InfraScorecard.markdown(rows);
        Files.writeString(out.resolve("scorecard.md"), md);
        System.out.println();
        for (InfraScorecard.Row r : rows) {
            System.out.printf("  %-8s %-13s %-45s %s%n", r.trap(), r.status(), r.title(), r.detail());
        }
        System.out.println(md.substring(md.lastIndexOf("phase 6:")).trim());
        System.out.println(findings.size() + " finding(s) written to " + out);
        return 0;
    }
}
