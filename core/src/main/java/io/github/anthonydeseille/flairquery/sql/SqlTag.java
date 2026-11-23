package io.github.anthonydeseille.flairquery.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Format and parser of the FlairQuery SQL tag:
 * <pre>/* fq:app=&lt;app&gt;,endpoint=&lt;GET /authors/{id}&gt;,caller=&lt;com.x.Svc#find:42&gt; *&#47; SELECT ...</pre>
 *
 * <p>Formatting and parsing live together (round-trip tested) so that the phase 2 collector can reuse
 * this class verbatim. Parsing is tolerant: unknown keys are kept, order does not matter.
 *
 * <p>Safety: values go through {@link #sanitize(String)}, an ASCII allow-list that excludes
 * {@code *} (PostgreSQL block comments nest, so {@code /*} inside a value would swallow the query),
 * quotes, backslash, {@code ?} (naive drivers may count it as a placeholder), {@code ;}, {@code ,}
 * and {@code =} (tag separators) and every control character.
 */
public final class SqlTag {

    public static final String PREFIX = "/* fq:";
    public static final String SUFFIX = " */ ";
    public static final int MAX_VALUE_LENGTH = 200;
    public static final String UNKNOWN = "unknown";

    private static final boolean[] ALLOWED = new boolean[128];

    static {
        for (char c = 'a'; c <= 'z'; c++) ALLOWED[c] = true;
        for (char c = 'A'; c <= 'Z'; c++) ALLOWED[c] = true;
        for (char c = '0'; c <= '9'; c++) ALLOWED[c] = true;
        for (char c : " _-./{}#:$<>[]@~+()|!^&".toCharArray()) ALLOWED[c] = true;
    }

    private SqlTag() { }

    /** Replaces every character outside the allow-list by {@code _}, collapses blanks, truncates. */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        int len = Math.min(value.length(), MAX_VALUE_LENGTH);
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            boolean ok = c < 128 && ALLOWED[c];
            if (!ok && sb == null) {
                sb = new StringBuilder(len).append(value, 0, i);
            }
            if (sb != null) {
                sb.append(ok ? c : '_');
            }
        }
        String out = sb != null ? sb.toString() : (len == value.length() ? value : value.substring(0, len));
        out = out.trim();
        return out.isEmpty() ? UNKNOWN : out;
    }

    public static boolean isTagged(String sql) {
        return sql != null && sql.startsWith(PREFIX);
    }

    /** Values must already be sanitized (hot path: no re-validation here). */
    public static String prefix(String app, String endpoint, String caller) {
        return PREFIX + "app=" + app + ",endpoint=" + endpoint + ",caller=" + caller + SUFFIX;
    }

    /** Convenience for tests and tools: sanitizes then tags. */
    public static String tag(String sql, String app, String endpoint, String caller) {
        if (isTagged(sql)) {
            return sql;
        }
        return prefix(sanitize(app), sanitize(endpoint), sanitize(caller)) + sql;
    }

    /** Finds the tag anywhere in a statement text (e.g. {@code pg_stat_statements.query}). */
    public static Optional<FqTag> parse(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        int start = sql.indexOf(PREFIX);
        if (start < 0) {
            return Optional.empty();
        }
        int bodyStart = start + PREFIX.length();
        int end = sql.indexOf("*/", bodyStart);
        if (end < 0) {
            return Optional.empty();
        }
        String body = sql.substring(bodyStart, end).trim();
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FqTag(values.get("app"), values.get("endpoint"), values.get("caller"), Map.copyOf(values)));
    }

    /** Removes a leading FlairQuery tag, if any. */
    public static String strip(String sql) {
        if (!isTagged(sql)) {
            return sql;
        }
        int end = sql.indexOf("*/", PREFIX.length());
        if (end < 0) {
            return sql;
        }
        int from = end + 2;
        while (from < sql.length() && sql.charAt(from) == ' ') from++;
        return sql.substring(from);
    }

    public record FqTag(String app, String endpoint, String caller, Map<String, String> all) { }
}
