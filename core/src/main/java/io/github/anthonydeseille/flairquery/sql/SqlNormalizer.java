package io.github.anthonydeseille.flairquery.sql;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalizes SQL for aggregation: FlairQuery tag removed, string / numeric literals and
 * {@code $n} parameters replaced by {@code ?}, {@code IN (?, ?, ...)} collapsed, whitespace collapsed.
 * Comments other than ours are kept (they are part of what the application sends).
 *
 * <p>Close to, but not identical to, {@code pg_stat_statements} normalization (which works on the
 * parse tree): the phase 2 collector correlates through the tag, not through the normalized text.
 */
public final class SqlNormalizer {

    static final int MAX_LENGTH = 4_000;
    static final int MAX_CACHE = 10_000;

    private final Map<String, Parsed> cache = new ConcurrentHashMap<>();

    /** Normalized SQL and caller (read back from the tag) of a statement text. */
    public record Parsed(String normalizedSql, String caller) { }

    public Parsed parse(String sql) {
        Parsed p = cache.get(sql);
        if (p != null) {
            return p;
        }
        String caller = SqlTag.parse(sql).map(SqlTag.FqTag::caller).orElse(SqlTag.UNKNOWN);
        p = new Parsed(normalize(SqlTag.strip(sql)), caller);
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(sql, p);
        return p;
    }

    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        int n = sql.length();
        StringBuilder out = new StringBuilder(Math.min(n, MAX_LENGTH));
        boolean pendingSpace = false;
        int i = 0;
        while (i < n && out.length() < MAX_LENGTH) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                i++;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            if (c == '\'') {                                   // string literal ('' escapes)
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append('?');
            } else if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)) && !identChar(prev(out))) {
                i++;                                            // $1 positional parameter
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                out.append('?');
            } else if (Character.isDigit(c) && !identChar(prev(out))) {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.append('?');                                // numeric literal
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') out.append(sql.charAt(i++));
                pendingSpace = true;                            // keep line comment, end it
            } else {
                out.append(c);
                i++;
            }
        }
        return collapseInLists(out.toString().trim());
    }

    /** {@code in (?, ?, ?)} → {@code in (?...)}: different list sizes are the same statement shape. */
    static String collapseInLists(String s) {
        if (s.indexOf('?') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('(', i);
            if (open < 0) {
                out.append(s, i, s.length());
                break;
            }
            int j = open + 1;
            int count = 0;
            boolean onlyParams = true;
            while (j < s.length() && s.charAt(j) != ')') {
                char c = s.charAt(j);
                if (c == '?') count++;
                else if (c != ',' && c != ' ') { onlyParams = false; break; }
                j++;
            }
            if (onlyParams && count > 1 && j < s.length()) {
                out.append(s, i, open).append("(?...)");
                i = j + 1;
            } else {
                out.append(s, i, open + 1);
                i = open + 1;
            }
        }
        return out.toString();
    }

    private static char prev(StringBuilder sb) {
        return sb.isEmpty() ? ' ' : sb.charAt(sb.length() - 1);
    }

    private static boolean identChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '"';
    }
}
