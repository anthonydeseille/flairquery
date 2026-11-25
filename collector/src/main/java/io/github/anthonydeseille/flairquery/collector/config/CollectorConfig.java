package io.github.anthonydeseille.flairquery.collector.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Configuration from FQ_COLLECTOR_* environment variables. */
public record CollectorConfig(
        List<Target> targets,
        String storeUrl,
        String storeUser,
        String storePassword,
        Duration interval,
        Duration retention,
        int httpPort,
        boolean readPlans,
        long logMaxBytesPerCycle,
        Duration columnStatsInterval,
        String agentUrl,
        boolean debug,
        Kubernetes kubernetes) {

    /**
     * Kubernetes reading (phase 6). {@code mode}: auto (when running in a pod with a service account), true, false.
     * {@code namespace} null: the collector's own namespace; {@code selector} null: every pod of the namespace.
     */
    public record Kubernetes(String mode, String namespace, String selector, boolean cadvisor, String agentPortName) {

        public boolean enabled(boolean inCluster) {
            return "true".equalsIgnoreCase(mode) || ("auto".equalsIgnoreCase(mode) && inCluster);
        }
    }

    public static CollectorConfig fromEnv(Map<String, String> env) {
        String url = required(env, "FQ_COLLECTOR_TARGET_URL");
        Target target = new Target(
                env.getOrDefault("FQ_COLLECTOR_TARGET_NAME", hostOf(url)),
                url,
                env.getOrDefault("FQ_COLLECTOR_TARGET_USER", "fq_reader"),
                env.getOrDefault("FQ_COLLECTOR_TARGET_PASSWORD", "fq_reader"));
        return new CollectorConfig(
                List.of(target),
                required(env, "FQ_COLLECTOR_STORE_URL"),
                env.getOrDefault("FQ_COLLECTOR_STORE_USER", "fqstore"),
                env.getOrDefault("FQ_COLLECTOR_STORE_PASSWORD", "fqstore"),
                Duration.ofSeconds(positive(env, "FQ_COLLECTOR_INTERVAL", 30)),
                Duration.ofHours(positive(env, "FQ_COLLECTOR_RETENTION_HOURS", 72)),
                (int) positive(env, "FQ_COLLECTOR_HTTP_PORT", 9465),
                !"false".equalsIgnoreCase(env.getOrDefault("FQ_COLLECTOR_PLANS", "true")),
                positive(env, "FQ_COLLECTOR_LOG_MAX_MB", 16) * 1024 * 1024,
                Duration.ofMinutes(positive(env, "FQ_COLLECTOR_COLUMN_STATS_MINUTES", 10)),
                blankToNull(env.get("FQ_COLLECTOR_AGENT_URL")),
                "debug".equalsIgnoreCase(env.getOrDefault("FQ_COLLECTOR_LOG_LEVEL", "info")),
                new Kubernetes(
                        env.getOrDefault("FQ_COLLECTOR_K8S", "auto").trim(),
                        blankToNull(env.get("FQ_COLLECTOR_K8S_NAMESPACE")),
                        blankToNull(env.get("FQ_COLLECTOR_K8S_SELECTOR")),
                        !"false".equalsIgnoreCase(env.getOrDefault("FQ_COLLECTOR_K8S_CADVISOR", "true")),
                        env.getOrDefault("FQ_COLLECTOR_AGENT_PORT_NAME", "fq-metrics").trim()));
    }

    static String hostOf(String jdbcUrl) {
        String rest = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
        int end = rest.indexOf('/');
        return end > 0 ? rest.substring(0, end) : rest;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String required(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required environment variable " + key);
        }
        return v.trim();
    }

    private static long positive(Map<String, String> env, String key, long def) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        long n = Long.parseLong(v.trim());
        if (n < 0) {
            throw new IllegalArgumentException(key + " must be >= 0");
        }
        return n;
    }
}
