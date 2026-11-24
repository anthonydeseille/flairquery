package io.github.anthonydeseille.flairquery.collector.infra;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses the kubelet's cAdvisor metrics (Prometheus text format, {@code /api/v1/nodes/{node}/proxy/metrics/cadvisor})
 * and keeps, per container of the watched pods, the few series used for sizing.
 */
public final class Cadvisor {

    /** cAdvisor series read (cumulative counters and gauges), keyed by the short name stored. */
    public static final Map<String, String> SERIES = Map.of(
            "container_cpu_usage_seconds_total", "cpuUsageSeconds",
            "container_cpu_cfs_periods_total", "cfsPeriods",
            "container_cpu_cfs_throttled_periods_total", "cfsThrottledPeriods",
            "container_cpu_cfs_throttled_seconds_total", "cfsThrottledSeconds",
            "container_memory_working_set_bytes", "memoryWorkingSetBytes",
            "container_memory_rss", "memoryRssBytes",
            "container_oom_events_total", "oomEvents");

    private Cadvisor() { }

    public record Key(String namespace, String pod, String container) { }

    /**
     * @param pods the pods watched ("namespace/pod"); other containers (and the pod-level "POD" / "" entries)
     *             are skipped
     */
    public static Map<Key, Map<String, Double>> parse(String text, Set<String> pods) {
        Map<Key, Map<String, Double>> out = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int brace = line.indexOf('{');
            if (brace < 0) continue;
            String short_ = SERIES.get(line.substring(0, brace));
            if (short_ == null) continue;
            int end = line.lastIndexOf('}');
            if (end < brace) continue;
            Map<String, String> labels = labels(line.substring(brace + 1, end));
            String container = labels.get("container");
            String pod = labels.get("pod");
            String ns = labels.get("namespace");
            if (container == null || container.isEmpty() || "POD".equals(container) || pod == null || ns == null) continue;
            if (!pods.contains(ns + "/" + pod)) continue;
            String[] rest = line.substring(end + 1).trim().split("\\s+");
            double value;
            try {
                value = Double.parseDouble(rest[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            // several cgroup entries can exist for one container (e.g. a restarted one): keep the largest counter
            out.computeIfAbsent(new Key(ns, pod, container), k -> new LinkedHashMap<>()).merge(short_, value, Math::max);
        }
        return out;
    }

    static Map<String, String> labels(String s) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            int eq = s.indexOf('=', i);
            if (eq < 0 || eq + 1 >= s.length() || s.charAt(eq + 1) != '"') break;
            String name = s.substring(i, eq).trim();
            StringBuilder v = new StringBuilder();
            int j = eq + 2;
            for (; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < s.length()) {
                    char n = s.charAt(++j);
                    v.append(n == 'n' ? '\n' : n);
                } else if (c == '"') {
                    break;
                } else {
                    v.append(c);
                }
            }
            m.put(name, v.toString());
            i = j + 1;
            while (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ' ')) i++;
        }
        return m;
    }
}
