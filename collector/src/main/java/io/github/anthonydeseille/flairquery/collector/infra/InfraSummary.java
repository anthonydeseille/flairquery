package io.github.anthonydeseille.flairquery.collector.infra;

import io.github.anthonydeseille.flairquery.collector.store.Store.StoredSample;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sizing signals over a window, computed from the raw samples: per workload container (requests and limits, memory
 * peaks, CPU usage percentiles from cAdvisor counter rates, CFS throttling ratio, restarts, OOM kills, startup time),
 * per JVM (heap, live set after GC, RSS, GC time ratio, HikariCP pool and peaks) and for the PostgreSQL server
 * (memory settings, temporary files written, cache hit ratio, connection peak against {@code max_connections}).
 * Facts only: the findings and recommendations are the {@code size} command's job.
 */
public final class InfraSummary {

    /** CFS periods (100 ms) of startup needed before a pod's startup throttling ratio is considered. */
    static final int MIN_STARTUP_PERIODS = 50;

    private InfraSummary() { }

    // ---------------------------------------------------------------- containers

    /**
     * Grouped by workload and container name, all replicas together. CPU usage and throttling are measured on the
     * steady intervals (container Ready); the startup intervals are reported apart ({@code startup}).
     *
     * @param since start of the window: a startup time is only reported for containers started inside it (a pod's
     *              Ready transition can be much later than its start when readiness was lost and regained)
     */
    public static List<Map<String, Object>> containers(List<StoredSample> samples, java.time.Instant since) {
        Map<String, List<StoredSample>> byGroup = new TreeMap<>();
        for (StoredSample s : samples) {
            Map<String, Object> p = s.payload();
            byGroup.computeIfAbsent(p.get("namespace") + "|" + p.get("workload") + "|" + p.get("container"),
                    k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<StoredSample> group : byGroup.values()) {
            out.add(containerGroup(group, since));
        }
        return out;
    }

    private static Map<String, Object> containerGroup(List<StoredSample> group, java.time.Instant since) {
        Map<String, List<StoredSample>> byPod = bySubject(group);
        StoredSample latest = group.get(group.size() - 1);
        Map<String, Object> lp = latest.payload();

        List<Double> cpuRates = new ArrayList<>();
        double periods = 0;
        double throttled = 0;
        double throttledSeconds = 0;
        double oomEvents = 0;
        double startupPeriods = 0;
        double startupThrottled = 0;
        Double startupCpu = null;
        List<Double> workingSet = new ArrayList<>();
        Double rssMax = null;
        int restarts = 0;
        int oomKilled = 0;
        Double startupMax = null;
        List<String> pods = new ArrayList<>();
        List<String> currentPods = new ArrayList<>();   // pods in the latest reading (others replaced or scaled down)
        List<Map<String, Object>> terminations = new ArrayList<>();
        Double worstStartupRatio = null;   // the most throttled startup, pod by pod (a crash-looping pod in back-off
                                           // would dilute an aggregate)
        for (Map.Entry<String, List<StoredSample>> e : byPod.entrySet()) {
            List<StoredSample> ss = e.getValue();
            double podStartupPeriods = 0;
            double podStartupThrottled = 0;
            Map<String, Object> last = ss.get(ss.size() - 1).payload();
            pods.add((String) last.get("pod"));
            if (ss.get(ss.size() - 1).takenAt().equals(latest.takenAt())) currentPods.add((String) last.get("pod"));
            restarts += intOf(last.get("restarts"));
            if (last.get("lastTermination") instanceof Map<?, ?> t) {
                Map<String, Object> term = new LinkedHashMap<>();
                term.put("pod", last.get("pod"));
                term.putAll(cast(t));
                terminations.add(term);
                if ("OOMKilled".equals(t.get("reason"))) oomKilled++;
            }
            Double startup = dbl(last.get("startupSeconds"));
            if (startup != null && !startedBefore(last, since)) startupMax = startupMax == null ? startup : Math.max(startupMax, startup);
            for (int i = 0; i < ss.size(); i++) {
                Map<String, Object> u = usage(ss.get(i));
                Double ws = dbl(u.get("memoryWorkingSetBytes"));
                if (ws != null) workingSet.add(ws);
                Double rss = dbl(u.get("memoryRssBytes"));
                if (rss != null) rssMax = rssMax == null ? rss : Math.max(rssMax, rss);
                if (i == 0) continue;
                StoredSample prev = ss.get(i - 1);
                if (!sameContainer(prev, ss.get(i))) continue;   // restarted: counters started again
                Map<String, Object> pu = usage(prev);
                double seconds = Duration.between(prev.takenAt(), ss.get(i).takenAt()).toMillis() / 1000.0;
                boolean inStartup = startupInterval(prev, ss.get(i).payload());
                Double dCpu = delta(pu, u, "cpuUsageSeconds");
                if (dCpu != null && seconds > 0) {
                    if (inStartup) startupCpu = maxOf(startupCpu, dCpu / seconds);
                    else cpuRates.add(dCpu / seconds);
                }
                Double dp = delta(pu, u, "cfsPeriods");
                Double dt = delta(pu, u, "cfsThrottledPeriods");
                if (dp != null && dt != null) {
                    if (inStartup) {
                        startupPeriods += dp;
                        startupThrottled += dt;
                        podStartupPeriods += dp;
                        podStartupThrottled += dt;
                    } else {
                        periods += dp;
                        throttled += dt;
                    }
                }
                Double ds = delta(pu, u, "cfsThrottledSeconds");
                if (ds != null && !inStartup) throttledSeconds += ds;
                Double doom = delta(pu, u, "oomEvents");
                if (doom != null) oomEvents += doom;
            }
            if (podStartupPeriods >= MIN_STARTUP_PERIODS) {
                worstStartupRatio = maxOf(worstStartupRatio, podStartupThrottled / podStartupPeriods);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("namespace", lp.get("namespace"));
        m.put("workload", lp.get("workload"));
        m.put("container", lp.get("container"));
        m.put("pods", pods);
        m.put("replicas", currentPods.size());
        m.put("currentPods", currentPods);
        m.put("requests", lp.get("requests"));
        m.put("limits", lp.get("limits"));
        m.put("qosClass", lp.get("qosClass"));
        m.put("samples", group.size());
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("workingSetMaxBytes", toLong(max(workingSet)));
        mem.put("workingSetP95Bytes", toLong(percentile(workingSet, 0.95)));
        mem.put("rssMaxBytes", toLong(rssMax));
        m.put("memory", mem);
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("usageP50Millis", millis(percentile(cpuRates, 0.50)));
        cpu.put("usageP95Millis", millis(percentile(cpuRates, 0.95)));
        cpu.put("usageMaxMillis", millis(max(cpuRates)));
        cpu.put("cfsPeriods", Math.round(periods));
        cpu.put("throttledPeriods", Math.round(throttled));
        cpu.put("throttledRatio", periods > 0 ? round4(throttled / periods) : null);
        cpu.put("throttledSeconds", round4(throttledSeconds));
        cpu.put("rateSamples", cpuRates.size());
        m.put("cpu", cpu);
        // intervals that started before the container was Ready: JVM startup (class loading, JIT), kept apart so a
        // startup burst is neither read as steady usage nor as steady throttling
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("cfsPeriods", Math.round(startupPeriods));
        st.put("throttledPeriods", Math.round(startupThrottled));
        st.put("throttledRatio", worstStartupRatio == null ? null : round4(worstStartupRatio));   // worst pod
        st.put("throttledRatioAllPods", startupPeriods > 0 ? round4(startupThrottled / startupPeriods) : null);
        st.put("usageMaxMillis", millis(startupCpu));
        m.put("startup", st);
        m.put("restarts", restarts);
        m.put("oomKilled", oomKilled);
        m.put("oomEvents", Math.round(oomEvents));
        m.put("lastTerminations", terminations);
        m.put("startupSecondsMax", startupMax);
        return m;
    }

    /** True when the interval starting at {@code prev} began before the container was Ready. */
    static boolean startupInterval(StoredSample prev, Map<String, Object> current) {
        if (!Boolean.TRUE.equals(prev.payload().get("ready"))) return true;
        Object at = current.get("startedAt");
        Double startup = dbl(current.get("startupSeconds"));
        if (at == null || startup == null) return false;
        try {
            java.time.Instant readyAt = java.time.Instant.parse(at.toString()).plusMillis(Math.round(startup * 1000));
            return prev.takenAt().isBefore(readyAt);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean startedBefore(Map<String, Object> p, java.time.Instant since) {
        Object at = p.get("startedAt");
        try {
            return at == null || java.time.Instant.parse(at.toString()).isBefore(since);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean sameContainer(StoredSample a, StoredSample b) {
        Object ia = a.payload().get("containerId");
        Object ib = b.payload().get("containerId");
        return ia == null || ia.equals(ib);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usage(StoredSample s) {
        return s.payload().get("usage") instanceof Map<?, ?> u ? (Map<String, Object>) u : Map.of();
    }

    // ---------------------------------------------------------------- JVMs

    /** One entry per pod (or agent URL). */
    public static List<Map<String, Object>> jvms(List<StoredSample> samples) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<StoredSample> ss : bySubject(samples).values()) {
            out.add(jvm(ss));
        }
        return out;
    }

    private static Map<String, Object> jvm(List<StoredSample> ss) {
        StoredSample latestSample = ss.get(ss.size() - 1);
        Map<String, Object> last = latestSample.payload();
        Map<String, Object> rt = map(last.get("runtime"));
        Map<String, Object> heap = map(last.get("heap"));

        Double heapUsedMax = null;
        Double liveSetMax = null;
        Double nonHeapMax = null;
        Double rssMax = null;
        Double metaspaceMax = null;
        int threadsPeak = 0;
        double gcMs = 0;
        double upMs = 0;
        for (int i = 0; i < ss.size(); i++) {
            Map<String, Object> p = ss.get(i).payload();
            heapUsedMax = maxOf(heapUsedMax, dbl(map(p.get("heap")).get("usedBytes")));
            nonHeapMax = maxOf(nonHeapMax, dbl(map(p.get("nonHeap")).get("usedBytes")));
            rssMax = maxOf(rssMax, dbl(map(p.get("runtime")).get("rssBytes")));
            threadsPeak = Math.max(threadsPeak, intOf(map(p.get("threads")).get("peak")));
            double afterGc = 0;
            boolean anyAfterGc = false;
            for (Map<String, Object> pool : list(p.get("pools"))) {
                Double after = dbl(pool.get("usedAfterGcBytes"));
                if ("HEAP".equals(pool.get("type")) && after != null) {
                    afterGc += after;
                    anyAfterGc = true;
                }
                if ("Metaspace".equals(pool.get("name"))) metaspaceMax = maxOf(metaspaceMax, dbl(pool.get("usedBytes")));
            }
            if (anyAfterGc) liveSetMax = maxOf(liveSetMax, afterGc);
            if (i > 0) {
                Map<String, Object> prev = ss.get(i - 1).payload();
                double up = num(map(p.get("runtime")).get("uptimeMs")) - num(map(prev.get("runtime")).get("uptimeMs"));
                if (up > 0) {   // a restart makes the uptime go down: that interval is skipped
                    upMs += up;
                    gcMs += Math.max(0, gcTime(p) - gcTime(prev));
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", latestSample.subject());
        m.put("app", last.get("app"));
        m.put("workload", last.get("workload"));
        m.put("samples", ss.size());
        m.put("containerMemoryBytes", toLong(dbl(rt.get("containerMemoryBytes"))));
        m.put("availableProcessors", rt.get("availableProcessors"));
        m.put("maxRamPercentage", flag(last, "MaxRAMPercentage"));
        m.put("maxRamPercentageOrigin", flagOrigin(last, "MaxRAMPercentage"));
        m.put("heapMaxBytes", toLong(dbl(heap.get("maxBytes"))));
        m.put("heapUsedMaxBytes", toLong(heapUsedMax));
        m.put("liveSetMaxBytes", toLong(liveSetMax));
        m.put("nonHeapUsedMaxBytes", toLong(nonHeapMax));
        m.put("metaspaceUsedMaxBytes", toLong(metaspaceMax));
        m.put("rssMaxBytes", toLong(rssMax));
        m.put("threadsPeak", threadsPeak);
        m.put("gcTimeRatio", upMs > 0 ? round4(gcMs / upMs) : null);
        m.put("gc", last.get("gc"));
        m.put("hikari", last.get("hikari"));
        return m;
    }

    private static double gcTime(Map<String, Object> p) {
        double t = 0;
        for (Map<String, Object> g : list(p.get("gc"))) t += num(g.get("timeMs"));
        return t;
    }

    private static Object flag(Map<String, Object> p, String name) {
        Map<String, Object> f = map(map(p.get("flags")).get(name));
        return f.get("value");
    }

    private static Object flagOrigin(Map<String, Object> p, String name) {
        return map(map(p.get("flags")).get(name)).get("origin");
    }

    // ---------------------------------------------------------------- PostgreSQL

    public static Map<String, Object> postgres(List<StoredSample> samples) {
        if (samples.isEmpty()) return null;
        Map<String, Object> first = samples.get(0).payload();
        Map<String, Object> last = samples.get(samples.size() - 1).payload();
        Map<String, Object> settings = map(last.get("settings"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", samples.size());
        m.put("settings", settings);

        Map<String, Object> d0 = map(first.get("database"));
        Map<String, Object> d1 = map(last.get("database"));
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("datname", d1.get("datname"));
        boolean sameStats = java.util.Objects.equals(d0.get("statsReset"), d1.get("statsReset"));
        db.put("statsResetInWindow", !sameStats);
        db.put("tempFiles", sameStats ? Math.round(num(d1.get("tempFiles")) - num(d0.get("tempFiles"))) : null);
        db.put("tempBytes", sameStats ? Math.round(num(d1.get("tempBytes")) - num(d0.get("tempBytes"))) : null);
        double hit = num(d1.get("blksHit")) - num(d0.get("blksHit"));
        double read = num(d1.get("blksRead")) - num(d0.get("blksRead"));
        db.put("cacheHitRatio", sameStats && hit + read > 0 ? round4(hit / (hit + read)) : null);
        db.put("deadlocks", sameStats ? Math.round(num(d1.get("deadlocks")) - num(d0.get("deadlocks"))) : null);
        m.put("database", db);

        long peak = 0;
        Map<String, Long> peakByApp = new TreeMap<>();
        Map<String, Long> peakByRole = new TreeMap<>();
        for (StoredSample s : samples) {
            peak = Math.max(peak, (long) num(s.payload().get("clientConnections")));
            Map<String, Long> app = new TreeMap<>();
            Map<String, Long> role = new TreeMap<>();
            for (Map<String, Object> c : list(s.payload().get("connections"))) {
                app.merge(String.valueOf(c.get("application")), (long) num(c.get("count")), Long::sum);
                role.merge(String.valueOf(c.get("role")), (long) num(c.get("count")), Long::sum);
            }
            app.forEach((k, v) -> peakByApp.merge(k, v, Math::max));
            role.forEach((k, v) -> peakByRole.merge(k, v, Math::max));
        }
        Map<String, Object> conns = new LinkedHashMap<>();
        conns.put("maxConnections", settingLong(settings, "max_connections"));
        conns.put("reserved", settingLong(settings, "superuser_reserved_connections")
                + settingLong(settings, "reserved_connections"));
        conns.put("clientPeak", peak);
        conns.put("current", (long) num(last.get("clientConnections")));
        conns.put("peakByApplication", peakByApp);
        conns.put("peakByRole", peakByRole);
        m.put("connections", conns);
        return m;
    }

    private static long settingLong(Map<String, Object> settings, String name) {
        Object s = map(settings.get(name)).get("setting");
        try {
            return s == null ? 0 : Long.parseLong(s.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, List<StoredSample>> bySubject(List<StoredSample> samples) {
        Map<String, List<StoredSample>> m = new TreeMap<>();
        for (StoredSample s : samples) m.computeIfAbsent(s.subject(), k -> new ArrayList<>()).add(s);
        for (List<StoredSample> l : m.values()) l.sort(Comparator.comparing(StoredSample::takenAt));
        return m;
    }

    /** Nearest-rank percentile, null when empty. */
    static Double percentile(Collection<Double> values, double p) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static Double max(Collection<Double> values) {
        return values.stream().max(Double::compare).orElse(null);
    }

    private static Double maxOf(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private static Double delta(Map<String, Object> prev, Map<String, Object> cur, String key) {
        Double a = dbl(prev.get(key));
        Double b = dbl(cur.get(key));
        if (a == null || b == null || b < a) return null;   // counter reset: interval skipped
        return b - a;
    }

    private static Long millis(Double cores) {
        return cores == null ? null : Math.round(cores * 1000);
    }

    private static Long toLong(Double v) {
        return v == null ? null : Math.round(v);
    }

    private static Double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
