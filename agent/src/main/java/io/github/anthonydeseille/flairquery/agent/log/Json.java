package io.github.anthonydeseille.flairquery.agent.log;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer (no dependency, so no classpath conflict with the application). */
public final class Json {

    private Json() { }

    public static void string(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static void value(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
                sb.append("null");
            } else {
                sb.append(v);
            }
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                value(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                value(sb, o);
            }
            sb.append(']');
        } else {
            string(sb, v.toString());
        }
    }

    public static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        value(sb, v);
        return sb.toString();
    }
}
