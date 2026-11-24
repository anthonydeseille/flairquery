package io.github.anthonydeseille.flairquery.agent.metrics;

import io.github.anthonydeseille.flairquery.agent.log.JsonLog;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JVM signals for infrastructure sizing (phase 6), read from the platform MXBeans only: heap and non-heap,
 * memory pools (peak and usage after the last GC, i.e. the live set), buffer pools, GC count and time, threads,
 * the memory and CPU the JVM sees (container-aware), the sizing flags ({@code MaxRAMPercentage}...), the process
 * RSS (Linux) and the HikariCP pools.
 *
 * <p>HikariCP is read through its MBeans ({@code com.zaxxer.hikari:type=Pool (name)}), registered only when the
 * pool is configured with {@code registerMbeans=true} (Spring Boot: {@code spring.datasource.hikari.register-mbeans}).
 * A background daemon thread samples them every second to keep the peaks (active connections, threads waiting
 * for a connection), which a 30-second collection cycle would miss.
 *
 * <p>Fail-safe: every section is read on its own; a failure leaves that section out (or null) and never reaches
 * the application. Nothing is read at agent startup: the MBean server is first touched by the sampler, after a
 * delay, or by an HTTP request.
 */
public final class JvmMetrics {

    /** VM options reported as they are (strings), when the JVM knows them. */
    static final List<String> FLAGS = List.of("MaxRAMPercentage", "InitialRAMPercentage", "MinRAMPercentage",
            "MaxHeapSize", "InitialHeapSize", "MaxMetaspaceSize", "MaxDirectMemorySize", "ReservedCodeCacheSize",
            "ThreadStackSize", "UseContainerSupport", "ActiveProcessorCount", "UseSerialGC", "UseG1GC",
            "UseParallelGC", "UseZGC");

    private static final String HIKARI_POOLS = "com.zaxxer.hikari:type=Pool (*";

    private final Map<String, int[]> hikariPeaks = new ConcurrentHashMap<>();   // pool -> {active, waiting}
    private ScheduledExecutorService sampler;

    /** Starts the HikariCP peak sampler (daemon thread). Never throws. */
    public synchronized void startSampler(long initialDelayMs, long periodMs) {
        if (sampler != null) return;
        try {
            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "flairquery-jvm-sampler");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleWithFixedDelay(this::sampleHikariSafely, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            JsonLog.debug("jvm sampler not started", "error", String.valueOf(t));
        }
    }

    public synchronized void stopSampler() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
    }

    void sampleHikariSafely() {
        try {
            for (Map<String, Object> pool : hikariPools(false)) {
                int active = intOf(pool.get("active"));
                int waiting = intOf(pool.get("waiting"));
                hikariPeaks.merge((String) pool.get("pool"), new int[]{active, waiting},
                        (a, b) -> new int[]{Math.max(a[0], b[0]), Math.max(a[1], b[1])});
            }
        } catch (Throwable t) {
            JsonLog.debug("hikari sample failed", "error", String.valueOf(t));
        }
    }

    /** One snapshot of every section (JSON-ready map). Never throws. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        section(m, "runtime", JvmMetrics::runtime);
        section(m, "flags", JvmMetrics::flags);
        section(m, "heap", () -> usage(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()));
        section(m, "nonHeap", () -> usage(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()));
        section(m, "pools", JvmMetrics::pools);
        section(m, "bufferPools", JvmMetrics::bufferPools);
        section(m, "gc", JvmMetrics::gc);
        section(m, "threads", JvmMetrics::threads);
        section(m, "hikari", () -> {
            sampleHikariSafely();
            return hikariPools(true);
        });
        return m;
    }

    private interface Section {
        Object read() throws Throwable;
    }

    private static void section(Map<String, Object> m, String name, Section s) {
        try {
            m.put(name, s.read());
        } catch (Throwable t) {
            m.put(name, null);
            JsonLog.debug("jvm metrics section unavailable", "section", name, "error", String.valueOf(t));
        }
    }

    static Map<String, Object> runtime() {
        Map<String, Object> m = new LinkedHashMap<>();
        var rt = ManagementFactory.getRuntimeMXBean();
        m.put("vmName", rt.getVmName());
        m.put("vmVersion", rt.getVmVersion());
        m.put("startTime", java.time.Instant.ofEpochMilli(rt.getStartTime()).toString());
        m.put("uptimeMs", rt.getUptime());
        var os = ManagementFactory.getOperatingSystemMXBean();
        m.put("availableProcessors", os.getAvailableProcessors());
        try {
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                // container-aware since JDK 14: the cgroup memory limit when there is one
                m.put("containerMemoryBytes", sun.getTotalMemorySize());
                m.put("processCpuLoad", round(sun.getProcessCpuLoad()));
                m.put("processCpuTimeMs", sun.getProcessCpuTime() / 1_000_000);
            }
        } catch (Throwable t) {
            // jdk.management absent: the standard values above are enough
        }
        m.put("rssBytes", rssBytes());
        return m;
    }

    /** Resident set size from /proc/self/status (Linux), null elsewhere. */
    static Long rssBytes() {
        try {
            Path status = Path.of("/proc/self/status");
            if (!Files.isReadable(status)) return null;
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    return Long.parseLong(parts[0]) * 1024;   // kB
                }
            }
        } catch (Throwable t) {
            // not Linux, or unreadable
        }
        return null;
    }

    static Map<String, Object> flags() {
        Map<String, Object> m = new LinkedHashMap<>();
        com.sun.management.HotSpotDiagnosticMXBean hs =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        if (hs == null) return m;
        for (String f : FLAGS) {
            try {
                var opt = hs.getVMOption(f);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", opt.getValue());
                o.put("origin", String.valueOf(opt.getOrigin()));
                m.put(f, o);
            } catch (Throwable t) {
                // unknown on this JVM
            }
        }
        return m;
    }

    static Map<String, Object> usage(MemoryUsage u) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (u == null) return null;
        m.put("usedBytes", u.getUsed());
        m.put("committedBytes", u.getCommitted());
        m.put("maxBytes", u.getMax() < 0 ? null : u.getMax());
        return m;
    }

    static List<Map<String, Object>> pools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            try {
                if (!p.isValid()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("type", p.getType().name());
                MemoryUsage u = p.getUsage();
                m.put("usedBytes", u.getUsed());
                m.put("committedBytes", u.getCommitted());
                m.put("maxBytes", u.getMax() < 0 ? null : u.getMax());
                MemoryUsage peak = p.getPeakUsage();
                m.put("peakUsedBytes", peak == null ? null : peak.getUsed());
                MemoryUsage afterGc = p.getCollectionUsage();   // null when the pool is not collected
                m.put("usedAfterGcBytes", afterGc == null ? null : afterGc.getUsed());
                out.add(m);
            } catch (Throwable t) {
                // pool gone or unreadable
            }
        }
        return out;
    }

    static List<Map<String, Object>> bufferPools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("count", b.getCount());
            m.put("usedBytes", b.getMemoryUsed());
            m.put("capacityBytes", b.getTotalCapacity());
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> gc() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.getName());
            m.put("count", Math.max(0, g.getCollectionCount()));
            m.put("timeMs", Math.max(0, g.getCollectionTime()));
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> threads() {
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("live", t.getThreadCount());
        m.put("peak", t.getPeakThreadCount());
        m.put("daemon", t.getDaemonThreadCount());
        m.put("startedTotal", t.getTotalStartedThreadCount());
        return m;
    }

    /** HikariCP pools registered as MBeans; with {@code withPeaks}, the peaks sampled since the agent started. */
    List<Map<String, Object>> hikariPools(boolean withPeaks) throws Exception {
        javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Set<javax.management.ObjectName> names = server.queryNames(new javax.management.ObjectName(HIKARI_POOLS), null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (javax.management.ObjectName name : names) {
            String type = name.getKeyProperty("type");             // "Pool (HikariPool-1)"
            String pool = type.substring(type.indexOf('(') + 1, type.length() - 1);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pool", pool);
            m.put("active", attribute(server, name, "ActiveConnections"));
            m.put("idle", attribute(server, name, "IdleConnections"));
            m.put("total", attribute(server, name, "TotalConnections"));
            m.put("waiting", attribute(server, name, "ThreadsAwaitingConnection"));
            javax.management.ObjectName config =
                    new javax.management.ObjectName("com.zaxxer.hikari:type=PoolConfig (" + pool + ")");
            m.put("maximumPoolSize", attribute(server, config, "MaximumPoolSize"));
            m.put("minimumIdle", attribute(server, config, "MinimumIdle"));
            m.put("connectionTimeoutMs", attribute(server, config, "ConnectionTimeout"));
            if (withPeaks) {
                int[] peaks = hikariPeaks.get(pool);
                m.put("peakActive", peaks == null ? null : peaks[0]);
                m.put("peakWaiting", peaks == null ? null : peaks[1]);
            }
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("pool")).compareTo(String.valueOf(b.get("pool"))));
        return out;
    }

    private static Object attribute(javax.management.MBeanServer server, javax.management.ObjectName name, String attr) {
        try {
            return server.getAttribute(name, attr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static Double round(double v) {
        return v < 0 ? null : Math.round(v * 10_000) / 10_000.0;
    }
}
