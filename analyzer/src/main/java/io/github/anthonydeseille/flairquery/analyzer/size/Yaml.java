package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Minimal block-style YAML writer for the Kustomize files FlairQuery generates (maps, lists, scalars). Every string is
 * double-quoted with JSON escapes, which YAML reads as the same string: no "1" read as a number, no "on" as a boolean.
 */
final class Yaml {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Yaml() { }

    static String write(Map<String, ?> root) {
        StringBuilder sb = new StringBuilder();
        map(sb, root, 0, false);
        return sb.toString();
    }

    private static void map(StringBuilder sb, Map<?, ?> m, int indent, boolean firstInline) {
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(first && firstInline)) sb.append(" ".repeat(indent));
            first = false;
            sb.append(e.getKey()).append(':');
            value(sb, e.getValue(), indent);
        }
    }

    private static void value(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) sb.append(" {}\n");
            else {
                sb.append('\n');
                map(sb, m, indent + 2, false);
            }
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) sb.append(" []\n");
            else {
                sb.append('\n');
                list(sb, l, indent + 2);
            }
        } else {
            sb.append(' ').append(scalar(v)).append('\n');
        }
    }

    private static void list(StringBuilder sb, List<?> l, int indent) {
        for (Object item : l) {
            sb.append(" ".repeat(indent)).append("- ");
            if (item instanceof Map<?, ?> m && !m.isEmpty()) {
                map(sb, m, indent + 2, true);
            } else if (item instanceof List<?>) {
                throw new IllegalArgumentException("nested lists are not supported");
            } else {
                sb.append(scalar(item)).append('\n');
            }
        }
    }

    static String scalar(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        try {
            return JSON.writeValueAsString(v.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
