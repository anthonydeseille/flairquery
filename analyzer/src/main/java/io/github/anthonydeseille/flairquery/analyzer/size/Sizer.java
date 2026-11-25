package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.anthonydeseille.flairquery.analyzer.size.Units.MI;

/**
 * Deterministic sizing rules (phase 6, no LLM): from the collector's {@code /fq/collector/infra} summary (and its
 * top statements by temporary blocks), findings with their evidence and a recommended configuration. Only the
 * long-lived workloads are sized (Deployment, StatefulSet, DaemonSet), never a Job or a bare Pod. A rule that lacks
 * data (too few samples) stays silent rather than guess. The thresholds are constants, documented in the README.
 *
 * <p>The recommendations are hypotheses: each one is replayed on kind before it can become a merge request (step 3).
 */
public final class Sizer {

    public enum Rule {
        /** I1: heap max + the JVM's native memory do not fit in the container memory limit. */
        JVM_HEAP_TOO_LARGE,
        /** I2: requests far below the measured usage (misleading scheduling). */
        REQUESTS_BELOW_USAGE,
        /** I3: CPU limit throttling the container in steady state or during a long startup. */
        CPU_THROTTLED,
        /** I4: sorts and hashes spilling to temporary files because of a small work_mem. */
        WORK_MEM_SPILLS,
        /** I5: shared_buffers tiny for the container memory, low cache hit ratio. */
        SHARED_BUFFERS_SMALL,
        /** I6: connection pools x replicas above what max_connections leaves to clients. */
        POOL_EXCEEDS_CONNECTIONS
    }

    // ---- thresholds
    /** Steady CPU rate samples (one per collection cycle) needed before sizing CPU. */
    static final int MIN_RATE_SAMPLES = 4;
    /** JVM native memory not covered by the measured non-heap: GC structures, malloc arenas, direct buffers. */
    static final long JVM_NATIVE_OVERHEAD = 64 * MI;
    /** Stack reserved per thread (default -Xss on 64-bit Linux). */
    static final long THREAD_STACK = MI;
    /** Margin on the native memory when deriving the heap. */
    static final double NATIVE_MARGIN = 1.25;
    /** The heap must hold at least this many times the live set (room for the GC to work). */
    static final double HEAP_OVER_LIVE_SET = 2.0;
    static final double CPU_REQUEST_RATIO = 0.5;       // flagged below p95 x this
    static final double MEMORY_REQUEST_RATIO = 0.8;    // flagged below working-set max x this
    static final double MEMORY_REQUEST_MARGIN = 1.15;
    /**
     * Steady throttling is flagged at or above this share of CFS periods, over enough periods, and only when the demand
     * presses on the limit (p95 at least {@link #SUSTAINED_DEMAND} of it): a mostly idle container whose short bursts
     * exhaust the quota of single 100 ms periods is throttled too (the classic false positive of a ratio-only alert),
     * without a sustained shortage of CPU.
     */
    static final double STEADY_THROTTLING = 0.10;
    static final double SUSTAINED_DEMAND = 0.50;
    static final long MIN_CFS_PERIODS = 100;
    static final double STARTUP_THROTTLING = 0.50;
    static final double SLOW_STARTUP_SECONDS = 30;
    static final double SHARED_BUFFERS_MIN_SHARE = 0.15;
    static final double SHARED_BUFFERS_SHARE = 0.25;
    static final double EFFECTIVE_CACHE_SHARE = 0.75;
    static final double CACHE_HIT_TARGET = 0.95;
    /** PostgreSQL memory outside shared_buffers and work_mem (processes, catalog caches, maintenance). */
    static final long PG_BASE_MEMORY = 64 * MI;
    /** Sort / hash nodes counted per connection when budgeting work_mem. */
    static final int WORK_MEM_NODES_PER_CONNECTION = 2;
    /** An in-memory sort needs more than its on-disk spill (tuple headers, pointers). */
    static final double IN_MEMORY_OVER_SPILL = 2.0;
    /** Connections kept free for administration and migrations, besides the reserved ones. */
    static final int ADMIN_CONNECTIONS = 3;

    private final String window;
    private final String postgresWorkload;   // null: the workload named after the collector's target

    public Sizer(String window, String postgresWorkload) {
        this.window = window;
        this.postgresWorkload = postgresWorkload;
    }

    /**
     * @param infra       {@code /fq/collector/infra?window=...}
     * @param tempQueries {@code /fq/collector/queries?order=temp&window=...} (may be empty)
     */
    public List<SizingFinding> size(JsonNode infra, JsonNode tempQueries) {
        List<SizingFinding> out = new ArrayList<>();
        Map<String, JsonNode> containers = new LinkedHashMap<>();
        for (JsonNode c : infra.path("containers")) {
            if (longLived(c.path("workload").asText())) {
                containers.put(c.path("workload").asText() + "/" + c.path("container").asText(), c);
            }
        }
        Map<String, List<JsonNode>> jvmsByWorkload = new LinkedHashMap<>();
        for (JsonNode j : infra.path("jvms")) {
            jvmsByWorkload.computeIfAbsent(j.path("workload").asText(j.path("subject").asText()), k -> new ArrayList<>()).add(j);
        }

        jvmsByWorkload.forEach((workload, jvms) -> heap(workload, jvms, containerOf(containers, workload)).ifPresent(out::add));
        for (Map.Entry<String, JsonNode> e : containers.entrySet()) {
            requests(e.getKey(), e.getValue()).ifPresent(out::add);
            throttling(e.getKey(), e.getValue()).ifPresent(out::add);
        }
        JsonNode pg = infra.path("postgres");
        if (!pg.isMissingNode() && !pg.isNull()) {
            String pgWorkload = postgresWorkload != null ? postgresWorkload : "Deployment/" + infra.path("target").asText();
            JsonNode pgContainer = containerOf(containers, pgWorkload);
            String target = "postgres:" + pgWorkload;
            workMem(target, pg, pgContainer, tempQueries).ifPresent(out::add);
            sharedBuffers(target, pg, pgContainer).ifPresent(out::add);
            pools(target, pg, jvmsByWorkload, containers).ifPresent(out::add);
        }
        return out;
    }

    static boolean longLived(String workload) {
        return workload.startsWith("Deployment/") || workload.startsWith("StatefulSet/") || workload.startsWith("DaemonSet/");
    }

    private static JsonNode containerOf(Map<String, JsonNode> containers, String workload) {
        JsonNode match = null;
        for (Map.Entry<String, JsonNode> e : containers.entrySet()) {
            if (e.getValue().path("workload").asText().equals(workload)) {
                if (match != null) return null;   // several containers: ambiguous
                match = e.getValue();
            }
        }
        return match;
    }

    // ---------------------------------------------------------------- I1

    java.util.Optional<SizingFinding> heap(String workload, List<JsonNode> jvms, JsonNode container) {
        // the replica that needs the most native memory decides
        JsonNode j = jvms.stream().max(java.util.Comparator.comparingLong(Sizer::nativeNeed)).orElseThrow();
        long limit = j.path("containerMemoryBytes").asLong(0);
        long heapMax = j.path("heapMaxBytes").asLong(0);
        if (limit <= 0 || heapMax <= 0 || j.path("nonHeapUsedMaxBytes").isMissingNode()) return java.util.Optional.empty();
        long nativeNeed = nativeNeed(j);
        int oomKilled = container == null ? 0 : container.path("oomKilled").asInt();
        boolean overcommitted = heapMax + nativeNeed > limit;
        if (!overcommitted && oomKilled == 0) return java.util.Optional.empty();

        long liveSet = Math.max(j.path("liveSetMaxBytes").asLong(0), 0);
        long nativeBudget = Math.round(nativeNeed * NATIVE_MARGIN);
        long targetHeap = limit - nativeBudget;
        Map<String, Object> rec = new LinkedHashMap<>();
        String rationale;
        if (targetHeap >= HEAP_OVER_LIVE_SET * liveSet && targetHeap > 0) {
            int pct = clampPct((int) Math.floor(100.0 * targetHeap / limit / 5) * 5);
            rec.put("MaxRAMPercentage", pct);
            rec.put("heapMax", Units.memory(limit * pct / 100));
            rec.put("memoryLimit", Units.memory(limit));
            rationale = "heap = limit - native x " + NATIVE_MARGIN + " (native = non-heap peak + " + j.path("threadsPeak").asInt()
                    + " thread stacks + " + Units.mib(JVM_NATIVE_OVERHEAD) + "), rounded down to 5 %; it still holds "
                    + HEAP_OVER_LIVE_SET + " x the live set";
        } else {
            long newLimit = Units.roundUp(Math.round(HEAP_OVER_LIVE_SET * liveSet) + nativeBudget, 64 * MI);
            int pct = clampPct((int) Math.floor(100.0 * (newLimit - nativeBudget) / newLimit / 5) * 5);
            rec.put("MaxRAMPercentage", pct);
            rec.put("memoryLimit", Units.memory(newLimit));
            rec.put("heapMax", Units.memory(newLimit * pct / 100));
            rationale = "the limit cannot hold " + HEAP_OVER_LIVE_SET + " x the live set plus the native memory: limit raised to "
                    + "fit both, heap share derived as above";
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("replicasMeasured", jvms.size());
        ev.put("containerMemory", Units.mib(limit));
        ev.put("heapMax", Units.mib(heapMax));
        ev.put("nonHeapPeak", Units.mib(j.path("nonHeapUsedMaxBytes").asLong()));
        ev.put("threadsPeak", j.path("threadsPeak").asInt());
        ev.put("nativeEstimate", Units.mib(nativeNeed));
        ev.put("headroomLeftByHeap", Units.mib(limit - heapMax));
        ev.put("liveSetPeak", Units.mib(liveSet));
        ev.put("heapUsedPeak", Units.mib(j.path("heapUsedMaxBytes").asLong()));
        if (j.hasNonNull("rssMaxBytes")) ev.put("rssPeak", Units.mib(j.path("rssMaxBytes").asLong()));
        if (container != null) {
            ev.put("oomKilled", oomKilled);
            ev.put("restarts", container.path("restarts").asInt());
        }
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("MaxRAMPercentage", j.path("maxRamPercentage").asText(null));
        cur.put("MaxRAMPercentageOrigin", j.path("maxRamPercentageOrigin").asText(null));
        cur.put("memoryLimit", Units.memory(limit));
        String title = overcommitted
                ? "Heap max (" + Units.mib(heapMax) + ") + native memory (~" + Units.mib(nativeNeed) + ") exceed the "
                        + Units.mib(limit) + " memory limit: a full heap gets the container OOMKilled"
                : "Container OOMKilled " + oomKilled + " time(s) with heap max " + Units.mib(heapMax) + " in "
                        + Units.mib(limit);
        String target = workload + "/" + (container == null ? "?" : container.path("container").asText());
        List<String> notes = new ArrayList<>(List.of("the live set and the non-heap are the peaks of the window: replay "
                + "a representative load before trusting them"));
        return java.util.Optional.of(new SizingFinding(id(Rule.JVM_HEAP_TOO_LARGE, target), Rule.JVM_HEAP_TOO_LARGE.name(),
                oomKilled > 0 ? "HIGH" : "MEDIUM", target, title, ev, cur, rec, rationale, notes));
    }

    static long nativeNeed(JsonNode j) {
        return j.path("nonHeapUsedMaxBytes").asLong(0) + j.path("threadsPeak").asLong(0) * THREAD_STACK + JVM_NATIVE_OVERHEAD;
    }

    private static int clampPct(int pct) {
        return Math.max(25, Math.min(75, pct));
    }

    // ---------------------------------------------------------------- I2

    java.util.Optional<SizingFinding> requests(String target, JsonNode c) {
        JsonNode cpu = c.path("cpu");
        if (cpu.path("rateSamples").asInt() < MIN_RATE_SAMPLES) return java.util.Optional.empty();
        long p95 = cpu.path("usageP95Millis").asLong(0);
        long wsMax = c.path("memory").path("workingSetMaxBytes").asLong(0);
        JsonNode req = c.path("requests");
        Long cpuReq = req.hasNonNull("cpuMillis") ? req.get("cpuMillis").asLong() : null;
        Long memReq = req.hasNonNull("memoryBytes") ? req.get("memoryBytes").asLong() : null;
        boolean cpuLow = p95 > 0 && (cpuReq == null || cpuReq < CPU_REQUEST_RATIO * p95);
        boolean memLow = wsMax > 0 && (memReq == null || memReq < MEMORY_REQUEST_RATIO * wsMax);
        if (!cpuLow && !memLow) return java.util.Optional.empty();

        Map<String, Object> rec = new LinkedHashMap<>();
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("cpuRequest", cpuReq == null ? null : Units.cpu(cpuReq));
        cur.put("memoryRequest", memReq == null ? null : Units.memory(memReq));
        List<String> notes = new ArrayList<>();
        if (cpuLow) rec.put("cpuRequest", Units.cpu(Units.roundUp(p95, 25)));
        if (memLow) {
            long memRec = Units.roundUp(Math.round(wsMax * MEMORY_REQUEST_MARGIN), 32 * MI);
            Long memLimit = c.path("limits").hasNonNull("memoryBytes") ? c.path("limits").get("memoryBytes").asLong() : null;
            if (memLimit != null && memRec > memLimit) {
                memRec = memLimit;
                notes.add("memory request capped at the memory limit");
            }
            rec.put("memoryRequest", Units.memory(memRec));
        }
        if (c.path("cpu").path("throttledRatio").asDouble(0) >= STEADY_THROTTLING) {
            notes.add("the CPU usage is capped by a throttling limit: the real demand may be higher (see CPU_THROTTLED)");
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("replicas", c.path("replicas").asInt());
        ev.put("cpuUsageP50", Units.cpu(cpu.path("usageP50Millis").asLong()));
        ev.put("cpuUsageP95", Units.cpu(p95));
        ev.put("cpuUsageMax", Units.cpu(cpu.path("usageMaxMillis").asLong()));
        ev.put("rateSamples", cpu.path("rateSamples").asInt());
        ev.put("workingSetPeak", Units.mib(wsMax));
        ev.put("qosClass", c.path("qosClass").asText(null));
        StringBuilder title = new StringBuilder("Requests far below the measured usage:");
        if (cpuLow) title.append(" CPU ").append(cpuReq == null ? "none" : Units.cpu(cpuReq)).append(" for a p95 of ").append(Units.cpu(p95));
        if (cpuLow && memLow) title.append(',');
        if (memLow) title.append(" memory ").append(memReq == null ? "none" : Units.memory(memReq)).append(" for a peak of ").append(Units.mib(wsMax));
        return java.util.Optional.of(new SizingFinding(id(Rule.REQUESTS_BELOW_USAGE, target), Rule.REQUESTS_BELOW_USAGE.name(),
                "MEDIUM", target, title.toString(), ev, cur, rec,
                "CPU request = steady p95 (rounded up to 25m); memory request = working-set peak x " + MEMORY_REQUEST_MARGIN
                        + " (rounded up to 32Mi, at most the limit)", notes));
    }

    // ---------------------------------------------------------------- I3

    java.util.Optional<SizingFinding> throttling(String target, JsonNode c) {
        JsonNode limits = c.path("limits");
        if (!limits.hasNonNull("cpuMillis")) return java.util.Optional.empty();
        long limit = limits.get("cpuMillis").asLong();
        JsonNode cpu = c.path("cpu");
        JsonNode st = c.path("startup");
        double steady = cpu.path("throttledRatio").asDouble(0);
        long p95Usage = cpu.path("usageP95Millis").asLong(0);
        boolean steadyThrottled = cpu.path("cfsPeriods").asLong(0) >= MIN_CFS_PERIODS && steady >= STEADY_THROTTLING
                && p95Usage >= SUSTAINED_DEMAND * limit;
        double startupRatio = st.path("throttledRatio").asDouble(0);
        double startupSeconds = c.path("startupSecondsMax").asDouble(0);
        boolean slowStartup = startupRatio >= STARTUP_THROTTLING && startupSeconds >= SLOW_STARTUP_SECONDS;
        if (!steadyThrottled && !slowStartup) return java.util.Optional.empty();

        long p95 = p95Usage;
        long recLimit = Units.roundUp(Math.max(2 * limit, Math.round(1.5 * p95)), 250);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("steadyThrottledRatio", steady);
        ev.put("steadyThrottledSeconds", cpu.path("throttledSeconds").asDouble());
        ev.put("steadyCfsPeriods", cpu.path("cfsPeriods").asLong());
        ev.put("startupThrottledRatio", startupRatio);
        ev.put("startupSeconds", startupSeconds);
        ev.put("cpuUsageP95", Units.cpu(p95));
        ev.put("cpuUsageP95OfLimit", Math.round(100.0 * p95 / limit) + " %");
        ev.put("cpuUsageMax", Units.cpu(cpu.path("usageMaxMillis").asLong()));
        String title = (steadyThrottled ? "CPU throttled " + pct(steady) + " of the time under load" : "")
                + (steadyThrottled && slowStartup ? ", and " : "")
                + (slowStartup ? (steadyThrottled ? "" : "CPU ") + "throttled " + pct(startupRatio) + " during a "
                        + Math.round(startupSeconds) + " s startup" : "")
                + " (limit " + Units.cpu(limit) + ")";
        return java.util.Optional.of(new SizingFinding(id(Rule.CPU_THROTTLED, target), Rule.CPU_THROTTLED.name(),
                "MEDIUM", target, title, ev, Map.of("cpuLimit", Units.cpu(limit)), Map.of("cpuLimit", Units.cpu(recLimit)),
                "a throttled container's usage is capped by its limit, so the real demand is unknown: the limit is doubled "
                        + "(at least 1.5 x the p95, rounded up to 250m) and the replay decides",
                List.of("the replay must show less throttling, a startup not slower and a p95 latency not worse")));
    }

    private static String pct(double ratio) {
        return Math.round(ratio * 100) + " %";
    }

    // ---------------------------------------------------------------- I4

    java.util.Optional<SizingFinding> workMem(String target, JsonNode pg, JsonNode pgContainer, JsonNode tempQueries) {
        JsonNode db = pg.path("database");
        long tempBytes = db.path("tempBytes").asLong(0);
        long tempFiles = db.path("tempFiles").asLong(0);
        long workMem = setting(pg, "work_mem");
        if (tempBytes <= 0 || tempFiles <= 0 || workMem <= 0) return java.util.Optional.empty();

        // the statement that spills the most per execution sets the need
        JsonNode top = null;
        double spillPerCall = 0;
        for (JsonNode q : tempQueries.path("queries")) {
            long calls = q.path("calls").asLong(0);
            long blocks = q.path("tempBlksWritten").asLong(0);
            if (calls > 0 && blocks > 0 && blocks * 8192.0 / calls > spillPerCall) {
                spillPerCall = blocks * 8192.0 / calls;
                top = q;
            }
        }
        long spill = spillPerCall > 0 ? Math.round(spillPerCall) : tempBytes / tempFiles;
        long need = Units.ceilPow2Mi(Math.round(spill * IN_MEMORY_OVER_SPILL));

        Long pgLimit = pgContainer == null || !pgContainer.path("limits").hasNonNull("memoryBytes") ? null
                : pgContainer.path("limits").get("memoryBytes").asLong();
        long peakConnections = Math.max(1, pg.path("connections").path("clientPeak").asLong(1));
        List<String> notes = new ArrayList<>();
        long recommended = need;
        Long budget = null;
        if (pgLimit != null) {
            long sharedBuffers = Math.max(setting(pg, "shared_buffers"), Math.round(pgLimit * SHARED_BUFFERS_SHARE));
            budget = (pgLimit - sharedBuffers - PG_BASE_MEMORY) / (peakConnections * WORK_MEM_NODES_PER_CONNECTION);
            if (budget > 0 && need > budget) {
                recommended = Units.floorPow2Mi(budget);
                notes.add("the memory limit only allows " + Units.pg(recommended) + " per sort for " + peakConnections
                        + " connections: the biggest sort will still spill; its code fix (an index and keyset pagination, "
                        + "trap 7) is the real remedy");
            }
        } else {
            notes.add("PostgreSQL container limit unknown: work_mem not checked against the memory budget");
        }
        if (recommended <= workMem) return java.util.Optional.empty();
        String role = appRole(pg);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("tempFiles", tempFiles);
        ev.put("tempWritten", Units.mib(tempBytes));
        ev.put("spillPerExecution", Units.mib(spill));
        if (top != null) {
            ev.put("topStatement", top.path("sql").asText().replaceAll("\\s+", " ").substring(0,
                    Math.min(120, top.path("sql").asText().replaceAll("\\s+", " ").length())));
            ev.put("topStatementEndpoint", top.path("endpoint").asText(null));
            ev.put("topStatementTempBlocks", top.path("tempBlksWritten").asLong());
            ev.put("topStatementCalls", top.path("calls").asLong());
        }
        ev.put("peakClientConnections", peakConnections);
        if (pgLimit != null) ev.put("containerMemory", Units.mib(pgLimit));
        if (budget != null) ev.put("workMemBudget", Units.mib(budget));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("work_mem", Units.pg(recommended));
        if (role != null) rec.put("sql", "ALTER ROLE " + role + " SET work_mem = '" + Units.pg(recommended) + "'");
        rec.put("restart", false);
        return java.util.Optional.of(new SizingFinding(id(Rule.WORK_MEM_SPILLS, target), Rule.WORK_MEM_SPILLS.name(),
                "MEDIUM", target, "Sorts spill to disk: " + Units.mib(tempBytes) + " of temporary files with work_mem "
                        + Units.pg(workMem), ev, Map.of("work_mem", Units.pg(workMem)), rec,
                "work_mem = the biggest spill per execution x " + IN_MEMORY_OVER_SPILL + " (power of two), within (container "
                        + "limit - shared_buffers - " + Units.mib(PG_BASE_MEMORY) + ") / (peak connections x "
                        + WORK_MEM_NODES_PER_CONNECTION + "); set per role, reloaded without a restart", notes));
    }

    /** The role with the most connections, other than the collector's read-only role. */
    static String appRole(JsonNode pg) {
        String best = null;
        long max = -1;
        var it = pg.path("connections").path("peakByRole").fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().equals("fq_reader") || e.getKey().isEmpty()) continue;
            if (e.getValue().asLong() > max) {
                max = e.getValue().asLong();
                best = e.getKey();
            }
        }
        return best;
    }

    private static long setting(JsonNode pg, String name) {
        JsonNode s = pg.path("settings").path(name);
        if (s.hasNonNull("bytes")) return s.get("bytes").asLong();
        try {
            return Long.parseLong(s.path("setting").asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- I5

    java.util.Optional<SizingFinding> sharedBuffers(String target, JsonNode pg, JsonNode pgContainer) {
        if (pgContainer == null || !pgContainer.path("limits").hasNonNull("memoryBytes")) return java.util.Optional.empty();
        long limit = pgContainer.path("limits").get("memoryBytes").asLong();
        long sb = setting(pg, "shared_buffers");
        JsonNode hit = pg.path("database").path("cacheHitRatio");
        if (sb <= 0 || hit.isMissingNode() || hit.isNull()) return java.util.Optional.empty();
        if (sb >= SHARED_BUFFERS_MIN_SHARE * limit || hit.asDouble() >= CACHE_HIT_TARGET) return java.util.Optional.empty();

        long recSb = Units.roundDown(Math.round(limit * SHARED_BUFFERS_SHARE), 16 * MI);
        long recEcs = Units.roundDown(Math.round(limit * EFFECTIVE_CACHE_SHARE), 16 * MI);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("cacheHitRatio", hit.asDouble());
        ev.put("sharedBuffers", Units.mib(sb));
        ev.put("containerMemory", Units.mib(limit));
        ev.put("workingSetPeak", Units.mib(pgContainer.path("memory").path("workingSetMaxBytes").asLong()));
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("shared_buffers", Units.pg(sb));
        cur.put("effective_cache_size", Units.pg(setting(pg, "effective_cache_size")));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("shared_buffers", Units.pg(recSb));
        rec.put("effective_cache_size", Units.pg(recEcs));
        rec.put("restart", true);
        return java.util.Optional.of(new SizingFinding(id(Rule.SHARED_BUFFERS_SMALL, target), Rule.SHARED_BUFFERS_SMALL.name(),
                "MEDIUM", target, "shared_buffers " + Units.pg(sb) + " for a " + Units.mib(limit) + " container: cache hit ratio "
                        + Math.round(hit.asDouble() * 1000) / 10.0 + " %", ev, cur, rec,
                "shared_buffers = " + Math.round(SHARED_BUFFERS_SHARE * 100) + " % of the container memory limit, "
                        + "effective_cache_size = " + Math.round(EFFECTIVE_CACHE_SHARE * 100) + " % (rounded down to 16 MB)",
                List.of("shared_buffers needs a PostgreSQL restart: merge request with human review only, never applied "
                        + "automatically")));
    }

    // ---------------------------------------------------------------- I6

    java.util.Optional<SizingFinding> pools(String target, JsonNode pg, Map<String, List<JsonNode>> jvmsByWorkload,
                                            Map<String, JsonNode> containers) {
        JsonNode conns = pg.path("connections");
        long maxConnections = conns.path("maxConnections").asLong(0);
        if (maxConnections <= 0) return java.util.Optional.empty();
        long reserved = conns.path("reserved").asLong(0);
        String appRole = appRole(pg);
        long others = 0;
        var it = conns.path("peakByRole").fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().equals(appRole)) others += e.getValue().asLong();
        }
        long available = maxConnections - reserved - others - ADMIN_CONNECTIONS;

        long total = 0;
        int peakWaiting = 0;
        List<Map<String, Object>> pools = new ArrayList<>();
        int replicasTotal = 0;
        boolean mixed = false;
        int unread = 0;
        for (Map.Entry<String, List<JsonNode>> e : jvmsByWorkload.entrySet()) {
            Map<String, Integer> poolByPod = new LinkedHashMap<>();
            int poolMax = 0;
            for (JsonNode j : e.getValue()) {
                int pod = 0;
                for (JsonNode p : j.path("hikari")) {
                    pod += p.path("maximumPoolSize").asInt();
                    peakWaiting = Math.max(peakWaiting, p.path("peakWaiting").asInt());
                }
                String name = j.path("subject").asText();
                poolByPod.put(name.substring(name.lastIndexOf('/') + 1), pod);
                poolMax = Math.max(poolMax, pod);
            }
            if (poolMax == 0) continue;
            JsonNode c = containerOf(containers, e.getKey());
            // the pods of the latest reading: each one's own pool; a pod not read (still starting, crashing) is
            // counted with the largest pool, which it opens as soon as it starts
            List<String> current = new ArrayList<>();
            if (c != null) c.path("currentPods").forEach(n -> current.add(n.asText()));
            if (current.isEmpty()) current.addAll(poolByPod.keySet());
            long workloadTotal = 0;
            java.util.Set<Integer> sizes = new java.util.TreeSet<>();
            for (String pod : current) {
                Integer size = poolByPod.get(pod);
                if (size == null) {
                    unread++;
                    size = poolMax;
                }
                sizes.add(size);
                workloadTotal += size;
            }
            mixed |= sizes.size() > 1;
            replicasTotal += current.size();
            total += workloadTotal;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("workload", e.getKey());
            p.put("maximumPoolSize", poolMax);
            p.put("replicas", current.size());
            p.put("poolSizes", sizes);
            p.put("connections", workloadTotal);
            pools.add(p);
        }
        if (pools.isEmpty() || total <= available) return java.util.Optional.empty();

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("window", window);
        ev.put("pools", pools);
        ev.put("poolConnectionsTotal", total);
        ev.put("maxConnections", maxConnections);
        ev.put("reserved", reserved);
        ev.put("otherClientsPeak", others);
        ev.put("adminMargin", ADMIN_CONNECTIONS);
        ev.put("availableForPools", available);
        ev.put("clientConnectionsPeak", conns.path("clientPeak").asLong());
        ev.put("threadsWaitingForAConnectionPeak", peakWaiting);
        ev.put("podsNotRead", unread);
        Map<String, Object> rec = new LinkedHashMap<>();
        long perReplica = Math.max(1, available / Math.max(1, replicasTotal));
        rec.put("maximumPoolSize", perReplica);
        rec.put("env", "HIKARI_MAX_POOL=" + perReplica);
        List<String> notes = new ArrayList<>(List.of("sized for the replicas measured; with an autoscaler use its "
                + "maximum replicas, or put a connection pooler (PgBouncer) in front"));
        if (pools.size() > 1) notes.add("several pooled workloads: the connections are shared evenly per replica");
        if (mixed) notes.add("pods with different pool sizes: a rollout was in progress (old and new pods together, as "
                + "during every rolling update): the sizing covers it");
        if (unread > 0) notes.add(unread + " pod(s) not read (starting or crashing) counted with the largest pool");
        return java.util.Optional.of(new SizingFinding(id(Rule.POOL_EXCEEDS_CONNECTIONS, target),
                Rule.POOL_EXCEEDS_CONNECTIONS.name(), "HIGH", target,
                "Connection pools can open " + total + " connections, PostgreSQL leaves " + available + " to them (max_connections "
                        + maxConnections + "): connections refused under load or at a rollout",
                ev, Map.of("maximumPoolSize", pools.get(0).get("maximumPoolSize"), "replicas", replicasTotal), rec,
                "pool per replica = (max_connections - reserved - other clients' peak - " + ADMIN_CONNECTIONS
                        + " for administration) / replicas", notes));
    }

    static String id(Rule rule, String target) {
        return rule.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-"
                + target.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
