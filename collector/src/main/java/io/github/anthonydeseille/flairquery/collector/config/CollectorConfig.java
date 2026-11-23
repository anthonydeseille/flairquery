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
        boolean debug) {

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
                positive(env, "FQ_COLLECTOR_LOG_MAX_MB", 64) * 1024 * 1024,
                "debug".equalsIgnoreCase(env.getOrDefault("FQ_COLLECTOR_LOG_LEVEL", "info")));
    }

    static String hostOf(String jdbcUrl) {
        String rest = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
        int end = rest.indexOf('/');
        return end > 0 ? rest.substring(0, end) : rest;
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
