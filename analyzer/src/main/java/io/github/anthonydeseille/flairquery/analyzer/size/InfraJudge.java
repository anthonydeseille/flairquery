package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.prove.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Decides whether sizing patches are proven (phase 6, step 3), from a replay without them (baseline) and one with
 * them (candidate): same cluster, same data, workloads restarted cold, same load. Accepted only when:
 * <ol>
 *   <li>each finding the patches answer is gone (and, for WORK_MEM_SPILLS, its statement no longer writes a single
 *       temporary block); for CPU_THROTTLED, the container is throttled less in steady state, or starts clearly
 *       faster, and is not throttled more in steady state (it may remain throttled: a JVM startup takes whatever CPU
 *       it is given, its startup throttling ratio is reported, not judged);</li>
 *   <li>every restarted workload rolled out: all replicas Ready, none restarted, none OOMKilled;</li>
 *   <li>no request failed (a refused connection fails a request);</li>
 *   <li>no request path got a worse p95 latency ({@link #LATENCY_NOISE} + {@link #LATENCY_NOISE_SECONDS});</li>
 *   <li>no startup got slower ({@link #STARTUP_NOISE} + {@link #STARTUP_NOISE_SECONDS});</li>
 *   <li>no new sizing finding on a restarted workload (the observers, never restarted, run differently in a replay).</li>
 * </ol>
 * Pure logic, unit-tested on synthetic measurements.
 */
public final class InfraJudge {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Tolerated relative p95 increase per request path (single runs on a laptop). */
    static final double LATENCY_NOISE = 0.25;
    static final double LATENCY_NOISE_SECONDS = 0.05;
    static final double STARTUP_NOISE = 0.20;
    /** Throttling ratio change (absolute) below which a ratio counts as unchanged. */
    static final double THROTTLING_NOISE = 0.05;
    static final double STARTUP_NOISE_SECONDS = 5;

    private InfraJudge() { }

    public static Verdict judge(List<SizingFinding> targets, InfraMeasurement before, InfraMeasurement after) {
        ObjectNode report = JSON.createObjectNode();
        report.put("phase", after.phase());
        ArrayNode fs = report.putArray("findings");
        targets.forEach(f -> fs.addObject().put("id", f.id()).put("rule", f.rule()).put("target", f.target()));
        ArrayNode checks = report.putArray("checks");
        List<String> reasons = new ArrayList<>();

        Set<String> beforeIds = ids(before.findings());
        Set<String> afterIds = ids(after.findings());
        List<String> missing = targets.stream().map(SizingFinding::id).filter(id -> !beforeIds.contains(id)).toList();
        if (!missing.isEmpty()) {
            String why = "not reproduced without the patch: " + missing;
            check(checks, "finding reproduced", false, why);
            report.put("status", Verdict.Status.INCONCLUSIVE.name());
            return new Verdict(Verdict.Status.INCONCLUSIVE, List.of(why), report);
        }

        // a throttled container is accepted when throttled less: its startup takes whatever CPU it is given, a limit
        // that removes every throttled period may not exist; the prover then tries the next step
        List<String> still = targets.stream().filter(f -> afterIds.contains(f.id())
                && !f.rule().equals(Sizer.Rule.CPU_THROTTLED.name())).map(SizingFinding::id).toList();
        List<String> reduced = targets.stream().filter(f -> afterIds.contains(f.id())
                && f.rule().equals(Sizer.Rule.CPU_THROTTLED.name())).map(SizingFinding::id).toList();
        check(checks, "finding gone", still.isEmpty(), !still.isEmpty() ? "still reported: " + still
                : reduced.isEmpty() ? "no longer reported" : "still reported, judged on the throttling reduction: " + reduced);
        if (!still.isEmpty()) reasons.add("finding still reported: " + still);
        report.put("stillReported", !reduced.isEmpty() || !still.isEmpty());

        for (SizingFinding f : targets) {
            if (f.rule().equals(Sizer.Rule.CPU_THROTTLED.name())) throttling(f, before, after, checks, reasons);
            if (f.rule().equals(Sizer.Rule.WORK_MEM_SPILLS.name())) spills(f, after, checks, reasons);
        }
        rollouts(before, after, checks, reasons, report);
        int errors = after.errors();
        check(checks, "no failed request", errors == 0, errors + " of " + after.requests() + " request(s) failed"
                + " (" + before.errors() + " without the patch)");
        if (errors > 0) reasons.add(errors + " request(s) failed");
        latency(before, after, checks, reasons, report);
        startup(before, after, checks, reasons, report);

        Set<String> added = new TreeSet<>(afterIds);
        added.removeAll(beforeIds);
        // a CPU rule needs enough steady samples: silent on the reference for lack of data is not "absent"
        List<String> unjudged = new ArrayList<>();
        List<String> observers = new ArrayList<>();
        for (SizingFinding f : after.findings()) {
            if (!added.contains(f.id())) continue;
            // only what the replay restarts is compared: an observer (the collector, its store) runs differently there
            if (!after.rollouts().containsKey(workload(f.target()))) {
                added.remove(f.id());
                observers.add(f.id());
                continue;
            }
            boolean cpuRule = f.rule().equals(Sizer.Rule.REQUESTS_BELOW_USAGE.name())
                    || f.rule().equals(Sizer.Rule.CPU_THROTTLED.name());
            if (cpuRule && before.container(f.target()).path("cpu").path("rateSamples").asInt(0) < Sizer.MIN_RATE_SAMPLES) {
                added.remove(f.id());
                unjudged.add(f.id());
            }
        }
        if (!unjudged.isEmpty()) report.set("notJudged", JSON.valueToTree(unjudged));
        if (!observers.isEmpty()) report.set("notRestarted", JSON.valueToTree(observers));
        String addedDetail = after.findings().stream().filter(f -> added.contains(f.id()))
                .map(f -> f.rule() + " on " + f.target() + ": " + f.title()).collect(Collectors.joining("; "));
        check(checks, "no new finding", added.isEmpty(), added.isEmpty()
                ? (unjudged.isEmpty() ? "none" : "none (not judged, too few CPU samples without the patch: " + unjudged + ")")
                : addedDetail);
        if (!added.isEmpty()) reasons.add("new finding: " + addedDetail);

        Verdict.Status status = reasons.isEmpty() ? Verdict.Status.ACCEPTED : Verdict.Status.REJECTED;
        report.put("status", status.name());
        report.set("reasons", JSON.valueToTree(reasons));
        return new Verdict(status, List.copyOf(reasons), report);
    }

    // ---------------------------------------------------------------- rule-specific

    private static void throttling(SizingFinding f, InfraMeasurement before, InfraMeasurement after, ArrayNode checks,
                                   List<String> reasons) {
        JsonNode b = before.container(f.target());
        JsonNode a = after.container(f.target());
        double bs = b.path("cpu").path("throttledRatio").asDouble(0);
        double as = a.path("cpu").path("throttledRatio").asDouble(0);
        double bu = b.path("startup").path("throttledRatio").asDouble(0);
        double au = a.path("startup").path("throttledRatio").asDouble(0);
        // a JVM startup takes whatever CPU it is given: its throttling ratio stays near 100 % whatever the limit
        // (reported, not judged); what a larger limit buys at startup is time
        double n = THROTTLING_NOISE;
        boolean steadyLess = as < bs - n;
        boolean steadyWorse = as > bs + n;
        double bt = b.path("startupSecondsMax").asDouble(Double.NaN);
        double at = a.path("startupSecondsMax").asDouble(Double.NaN);
        boolean startupFaster = !Double.isNaN(bt) && !Double.isNaN(at)
                && at < bt * (1 - STARTUP_NOISE) - STARTUP_NOISE_SECONDS;
        boolean less = !a.isMissingNode() && !steadyWorse && (steadyLess || startupFaster);
        String detail = String.format(Locale.ROOT, "%s: steady %s -> %s; startup %s -> %s (throttled %s -> %s)",
                f.target(), pct(bs), pct(as), secs(bt), secs(at), pct(bu), pct(au));
        check(checks, "less throttling", less, a.isMissingNode() ? f.target() + " not measured with the patch" : detail);
        if (!less) reasons.add("throttling not reduced: " + detail);
    }

    private static void spills(SizingFinding f, InfraMeasurement after, ArrayNode checks, List<String> reasons) {
        Object endpoint = f.evidence() == null ? null : f.evidence().get("topStatementEndpoint");
        long blocks = 0;
        String what;
        if (endpoint != null) {
            for (JsonNode q : after.tempQueries().path("queries")) {
                if (endpoint.toString().equals(q.path("endpoint").asText())) blocks += q.path("tempBlksWritten").asLong();
            }
            what = "statements of " + endpoint;
        } else {
            blocks = after.infra().path("postgres").path("database").path("tempBytes").asLong(0) / 8192;
            what = "database";
        }
        boolean none = blocks == 0;
        String detail = what + ": " + blocks + " temporary block(s) written (" + Units.mib(blocks * 8192.0) + ")";
        check(checks, "no spill (temp_blks_written 0)", none, detail);
        if (!none) {
            reasons.add("still spills to disk: " + detail + (f.notes() == null || f.notes().isEmpty() ? ""
                    : "; " + String.join("; ", f.notes())));
        }
    }

    // ---------------------------------------------------------------- general

    private static void rollouts(InfraMeasurement before, InfraMeasurement after, ArrayNode checks, List<String> reasons,
                                 ObjectNode report) {
        List<String> failed = new ArrayList<>();
        List<String> crashed = new ArrayList<>();
        ObjectNode r = report.putObject("rollouts");
        for (Map.Entry<String, InfraMeasurement.Rollout> e : after.rollouts().entrySet()) {
            InfraMeasurement.Rollout b = before.rollouts().get(e.getKey());
            r.putObject(e.getKey()).put("before", b == null ? null : b.detail()).put("after", e.getValue().detail());
            if (!e.getValue().complete()) failed.add(e.getKey() + " (" + e.getValue().detail() + ")");
            for (JsonNode c : after.infra().path("containers")) {
                if (!c.path("workload").asText().equals(e.getKey())) continue;
                int restarts = c.path("restarts").asInt(0);
                int oom = c.path("oomKilled").asInt(0);
                if (restarts > 0 || oom > 0) {
                    crashed.add(e.getKey() + "/" + c.path("container").asText() + ": " + restarts + " restart(s), "
                            + oom + " OOMKilled");
                }
            }
        }
        check(checks, "rolled out", failed.isEmpty(), failed.isEmpty() ? after.rollouts().keySet() + " Ready" : String.join("; ", failed));
        failed.forEach(x -> reasons.add("rollout not complete: " + x));
        check(checks, "no restart, no OOMKill", crashed.isEmpty(), crashed.isEmpty() ? "none" : String.join("; ", crashed));
        crashed.forEach(x -> reasons.add("container restarted: " + x));
    }

    private static void latency(InfraMeasurement before, InfraMeasurement after, ArrayNode checks, List<String> reasons,
                                ObjectNode report) {
        ArrayNode table = report.putArray("latency");
        List<String> worse = new ArrayList<>();
        Set<String> paths = new TreeSet<>(before.latencies().keySet());
        paths.addAll(after.latencies().keySet());
        for (String p : paths) {
            double b = InfraMeasurement.percentile(before.latencies().get(p), 0.95);
            double a = InfraMeasurement.percentile(after.latencies().get(p), 0.95);
            ObjectNode row = table.addObject().put("path", p);
            if (!Double.isNaN(b)) row.put("p95Before", round3(b));
            if (!Double.isNaN(a)) row.put("p95After", round3(a));
            if (Double.isNaN(b)) continue;   // nothing to compare with (failed without the patch)
            if (Double.isNaN(a)) {
                worse.add(p + ": no successful request with the patch");
            } else if (a > b * (1 + LATENCY_NOISE) + LATENCY_NOISE_SECONDS) {
                worse.add(String.format(Locale.ROOT, "%s: p95 %.3f s instead of %.3f s", p, a, b));
            }
        }
        check(checks, "p95 latency not worse", worse.isEmpty(), worse.isEmpty() ? paths.size() + " request path(s) compared"
                : String.join("; ", worse));
        worse.forEach(w -> reasons.add("slower: " + w));
    }

    private static void startup(InfraMeasurement before, InfraMeasurement after, ArrayNode checks, List<String> reasons,
                                ObjectNode report) {
        ArrayNode table = report.putArray("startup");
        List<String> slower = new ArrayList<>();
        for (String workload : after.rollouts().keySet()) {
            InfraMeasurement.Rollout br = before.rollouts().get(workload);
            for (JsonNode a : after.infra().path("containers")) {
                if (!a.path("workload").asText().equals(workload) || !a.hasNonNull("startupSecondsMax")) continue;
                String target = workload + "/" + a.path("container").asText();
                JsonNode b = before.container(target);
                ObjectNode row = table.addObject().put("container", target)
                        .put("secondsAfter", a.path("startupSecondsMax").asDouble());
                if (!b.hasNonNull("startupSecondsMax") || br == null || !br.complete()) continue;
                double bs = b.path("startupSecondsMax").asDouble();
                double as = a.path("startupSecondsMax").asDouble();
                row.put("secondsBefore", bs);
                if (as > bs * (1 + STARTUP_NOISE) + STARTUP_NOISE_SECONDS) {
                    slower.add(String.format(Locale.ROOT, "%s: %.0f s instead of %.0f s", target, as, bs));
                }
            }
        }
        check(checks, "startup not slower", slower.isEmpty(), slower.isEmpty() ? "none slower" : String.join("; ", slower));
        slower.forEach(s -> reasons.add("slower startup: " + s));
    }

    // ---------------------------------------------------------------- control

    /**
     * The replay's own noise: the baseline played twice (first and last phase), same configuration. Informational:
     * a verdict whose margins are within this drift is fragile.
     */
    public static ObjectNode drift(InfraMeasurement first, InfraMeasurement again) {
        ObjectNode d = JSON.createObjectNode();
        ObjectNode startup = d.putObject("startupSeconds");
        ObjectNode steady = d.putObject("steadyThrottledRatio");
        for (JsonNode c : first.infra().path("containers")) {
            String target = c.path("workload").asText() + "/" + c.path("container").asText();
            if (!first.rollouts().containsKey(c.path("workload").asText())) continue;
            JsonNode a = again.container(target);
            if (c.hasNonNull("startupSecondsMax") && a.hasNonNull("startupSecondsMax")) {
                startup.putArray(target).add(c.path("startupSecondsMax").asDouble()).add(a.path("startupSecondsMax").asDouble());
            }
            steady.putArray(target).add(c.path("cpu").path("throttledRatio").asDouble(0))
                    .add(a.path("cpu").path("throttledRatio").asDouble(0));
        }
        double worst = 0;
        String worstPath = null;
        for (String p : first.latencies().keySet()) {
            double b = InfraMeasurement.percentile(first.latencies().get(p), 0.95);
            double a = InfraMeasurement.percentile(again.latencies().get(p), 0.95);
            // a few milliseconds on a fast path are not a relative drift worth reporting
            if (Double.isNaN(a) || Double.isNaN(b) || b <= 0 || Math.abs(a - b) <= LATENCY_NOISE_SECONDS) continue;
            double rel = Math.abs(a - b) / b;
            if (rel > worst) {
                worst = rel;
                worstPath = p;
            }
        }
        d.put("p95MaxRelativeDifference", Math.round(worst * 1000) / 1000.0);
        if (worstPath != null) d.put("p95MaxDifferencePath", worstPath);
        return d;
    }

    // ---------------------------------------------------------------- helpers

    private static Set<String> ids(List<SizingFinding> findings) {
        Set<String> s = new TreeSet<>();
        findings.forEach(f -> s.add(f.id()));
        return s;
    }

    private static String pct(double ratio) {
        return Math.round(ratio * 1000) / 10.0 + " %";
    }

    /** "Deployment/demo-app/demo-app" and "postgres:Deployment/postgres" -> "Deployment/demo-app", "Deployment/postgres". */
    static String workload(String target) {
        String t = target.startsWith("postgres:") ? target.substring("postgres:".length()) : target;
        String[] p = t.split("/");
        return p.length >= 2 ? p[0] + "/" + p[1] : t;
    }

    private static String secs(double s) {
        return Double.isNaN(s) ? "?" : Math.round(s) + " s";
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static void check(ArrayNode checks, String name, boolean passed, String detail) {
        checks.addObject().put("check", name).put("passed", passed).put("detail", detail);
    }
}
