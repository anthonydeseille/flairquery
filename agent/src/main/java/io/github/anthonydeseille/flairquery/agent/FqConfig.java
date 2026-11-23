package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.log.JsonLog;
import io.github.anthonydeseille.flairquery.sql.SqlTag;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent configuration, read from {@code FQ_*} environment variables.
 * An invalid value is logged and replaced by its default: configuration never prevents startup.
 */
public record FqConfig(
        boolean enabled,
        String appName,
        List<String> basePackages,
        int nPlusOneThreshold,
        double samplingRate,
        int httpPort,
        JsonLog.Level logLevel,
        Set<String> testFaults) {

    public static final int DEFAULT_NPLUSONE_THRESHOLD = 5;
    public static final double DEFAULT_SAMPLING_RATE = 1.0;
    public static final int DEFAULT_HTTP_PORT = 9464;
    public static final String DEFAULT_APP_NAME = "unknown-app";

    public static FqConfig fromEnv(Map<String, String> env) {
        boolean enabled = parseBoolean(env, "FQ_ENABLED", true);
        String app = env.getOrDefault("FQ_APP_NAME", "").trim();
        app = app.isEmpty() ? DEFAULT_APP_NAME : SqlTag.sanitize(app);

        List<String> packages = splitList(env.get("FQ_BASE_PACKAGES"));

        int threshold = parseInt(env, "FQ_NPLUSONE_THRESHOLD", DEFAULT_NPLUSONE_THRESHOLD, 1, Integer.MAX_VALUE);
        double sampling = parseDouble(env, "FQ_SAMPLING_RATE", DEFAULT_SAMPLING_RATE, 0.0, 1.0);
        int port = parseInt(env, "FQ_HTTP_PORT", DEFAULT_HTTP_PORT, 0, 65535);

        JsonLog.Level level = JsonLog.Level.INFO;
        String rawLevel = env.get("FQ_LOG_LEVEL");
        if (rawLevel != null && !rawLevel.isBlank()) {
            try {
                level = JsonLog.Level.valueOf(rawLevel.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                invalid("FQ_LOG_LEVEL", rawLevel, level);
            }
        }
        // test-only: forces internal failures to prove the fail-safe contract
        Set<String> faults = Set.copyOf(splitList(env.get("FQ_TEST_FAULT")));

        return new FqConfig(enabled, app, packages, threshold, sampling, port, level, faults);
    }

    public static FqConfig defaults() {
        return fromEnv(Map.of());
    }

    public boolean fault(String name) {
        return testFaults.contains(name);
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .map(s -> s.endsWith(".") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private static boolean parseBoolean(Map<String, String> env, String key, boolean def) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                invalid(key, raw, def);
                yield def;
            }
        };
    }

    private static int parseInt(Map<String, String> env, String key, int def, int min, int max) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= min && v <= max) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        invalid(key, raw, def);
        return def;
    }

    private static double parseDouble(Map<String, String> env, String key, double def, double min, double max) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v >= min && v <= max) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        invalid(key, raw, def);
        return def;
    }

    private static void invalid(String key, String raw, Object def) {
        JsonLog.warn("invalid configuration value, using default", null, "key", key, "value", raw, "default", def);
    }
}
