package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 scorecard of the demo's infrastructure traps (ground truth: {@code demo-app/TRAPS.md}, I1-I6): a trap is
 * DETECTED when the {@code size} command reports its rule on its target. I6 only exists with the
 * {@code pool-overflow} overlay (3 replicas): NOT_DEPLOYED otherwise. I5 only hurts when the data outgrows
 * shared_buffers (profile M): NOT_TRIGGERED when the cache hit ratio stays at its target, as on profile S. The witness
 * (the collector) must stay CLEAN.
 */
public final class InfraScorecard {

    public enum Status { DETECTED, NOT_DETECTED, NOT_DEPLOYED, NOT_TRIGGERED, CLEAN, FLAGGED }

    public record Trap(String id, String title, Sizer.Rule rule, String target) { }

    public static final String DEMO = "Deployment/demo-app/demo-app";
    public static final String WITNESS = "Deployment/fq-collector";

    public static final List<Trap> TRAPS = List.of(
            new Trap("I1", "MaxRAMPercentage=80 with a 768Mi limit", Sizer.Rule.JVM_HEAP_TOO_LARGE, DEMO),
            new Trap("I2", "Requests 25m / 64Mi far below usage", Sizer.Rule.REQUESTS_BELOW_USAGE, DEMO),
            new Trap("I3", "CPU limit 500m (throttling, slow startup)", Sizer.Rule.CPU_THROTTLED, DEMO),
            new Trap("I4", "work_mem 64kB (trap 7 spills)", Sizer.Rule.WORK_MEM_SPILLS, "postgres:"),
            new Trap("I5", "shared_buffers 32MB", Sizer.Rule.SHARED_BUFFERS_SMALL, "postgres:"),
            new Trap("I6", "Hikari 20 x 3 replicas > max_connections 50", Sizer.Rule.POOL_EXCEEDS_CONNECTIONS, "postgres:"));

    public record Row(String trap, String title, Status status, String detail) { }

    private InfraScorecard() { }

    public static List<Row> of(List<SizingFinding> findings, JsonNode infra) {
        List<Row> rows = new ArrayList<>();
        for (Trap t : TRAPS) {
            SizingFinding f = findings.stream()
                    .filter(x -> x.rule().equals(t.rule().name()) && x.target().startsWith(t.target()))
                    .findFirst().orElse(null);
            if (f != null) {
                rows.add(new Row(t.id(), t.title(), Status.DETECTED, f.title()));
            } else if (t.id().equals("I5") && cacheHit(infra) >= Sizer.CACHE_HIT_TARGET) {
                rows.add(new Row(t.id(), t.title(), Status.NOT_TRIGGERED, "cache hit ratio " + Math.round(cacheHit(infra) * 1000) / 10.0
                        + " % with these data: they fit in shared_buffers (profile S); visible on profile M"));
            } else if (t.id().equals("I6") && demoReplicas(infra) < 3) {
                rows.add(new Row(t.id(), t.title(), Status.NOT_DEPLOYED,
                        "the pool-overflow overlay is not deployed (" + demoReplicas(infra) + " demo-app replica)"));
            } else {
                rows.add(new Row(t.id(), t.title(), Status.NOT_DETECTED, "no " + t.rule() + " finding on " + t.target()));
            }
        }
        List<SizingFinding> witness = findings.stream().filter(f -> f.target().startsWith(WITNESS + "/")).toList();
        rows.add(new Row("witness", "Collector (" + WITNESS + ")", witness.isEmpty() ? Status.CLEAN : Status.FLAGGED,
                witness.isEmpty() ? "never flagged" : witness.get(0).rule() + ": " + witness.get(0).title()));
        return rows;
    }

    static double cacheHit(JsonNode infra) {
        return infra.path("postgres").path("database").path("cacheHitRatio").asDouble(0);
    }

    static int demoReplicas(JsonNode infra) {
        for (JsonNode c : infra.path("containers")) {
            if ((c.path("workload").asText() + "/" + c.path("container").asText()).equals(DEMO)) {
                return c.path("replicas").asInt();
            }
        }
        return 0;
    }

    public static String markdown(List<Row> rows) {
        StringBuilder sb = new StringBuilder("# Infrastructure trap scorecard (phase 6, `size`)\n\n")
                .append("| Trap | Title | Status | Detail |\n|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.trap()).append(" | ").append(r.title()).append(" | ").append(r.status())
              .append(" | ").append(r.detail().replace("|", "\\|")).append(" |\n");
        }
        long detected = rows.stream().filter(r -> r.status() == Status.DETECTED).count();
        long inScope = rows.stream().filter(r -> r.trap().startsWith("I") && r.status() != Status.NOT_DEPLOYED
                && r.status() != Status.NOT_TRIGGERED).count();
        boolean clean = rows.stream().anyMatch(r -> r.status() == Status.CLEAN);
        sb.append("\nphase 6: ").append(detected).append('/').append(inScope).append(" infrastructure traps detected")
          .append(clean ? ", witness clean" : ", WITNESS FLAGGED").append('\n');
        return sb.toString();
    }
}
