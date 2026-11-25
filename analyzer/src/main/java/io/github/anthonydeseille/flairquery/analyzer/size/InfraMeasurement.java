package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One replay phase on kind (phase 6, step 3): the workloads restarted cold with one configuration, the same load,
 * then what the collector measured over the phase.
 *
 * @param phase      "baseline", a finding id, or "combined"
 * @param window     collector window covering the phase, from the cold start ("312s")
 * @param infra      {@code /fq/collector/infra} over the window
 * @param tempQueries {@code /fq/collector/queries?order=temp} over the window
 * @param findings   the sizing findings on this measurement ({@link Sizer})
 * @param latencies  seconds per request, by request path, warm-up round excluded
 * @param requests   requests sent (warm-up included)
 * @param errors     requests that did not answer HTTP 200 (warm-up included)
 * @param rollouts   per restarted workload: true when every replica became Ready without restarting
 */
public record InfraMeasurement(String phase, String window, JsonNode infra, JsonNode tempQueries,
                               List<SizingFinding> findings, Map<String, List<Double>> latencies, int requests,
                               int errors, Map<String, Rollout> rollouts) {

    /** @param detail "3/3 Ready" or why not */
    public record Rollout(boolean complete, String detail) { }

    /**
     * Parses the load job output: {@code timing<TAB>round<TAB>path<TAB>status<TAB>seconds} lines
     * ({@code FQ_TRAFFIC_TIMINGS=1 scripts/traffic.sh}); the first round warms the JVM up and is not timed.
     */
    public record Load(Map<String, List<Double>> latencies, int requests, int errors) {
        public static Load parse(String output) {
            Map<String, List<Double>> lat = new LinkedHashMap<>();
            int requests = 0;
            int errors = 0;
            for (String line : output.split("\n")) {
                String[] p = line.split("\t");
                if (p.length != 5 || !p[0].equals("timing")) continue;
                requests++;
                if (!p[3].equals("200")) {
                    errors++;
                    continue;
                }
                if (p[1].equals("1")) continue;   // warm-up round
                try {
                    lat.computeIfAbsent(p[2], k -> new ArrayList<>()).add(Double.parseDouble(p[4].replace(',', '.')));
                } catch (NumberFormatException ignored) {
                    // not a timing
                }
            }
            return new Load(lat, requests, errors);
        }
    }

    /** Nearest-rank percentile, NaN when empty. */
    static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(values);
        s.sort(Double::compare);
        int rank = (int) Math.ceil(p * s.size());
        return s.get(Math.max(0, Math.min(s.size() - 1, rank - 1)));
    }

    /** The infra summary of a container ("Deployment/demo-app/demo-app"), missing node when absent. */
    JsonNode container(String target) {
        for (JsonNode c : infra.path("containers")) {
            if ((c.path("workload").asText() + "/" + c.path("container").asText()).equals(target)) return c;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
