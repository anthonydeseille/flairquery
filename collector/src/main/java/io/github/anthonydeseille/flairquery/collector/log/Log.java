package io.github.anthonydeseille.flairquery.collector.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** One-line JSON logs on stdout (same shape as the agent's). */
public final class Log {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile boolean debug = false;

    private Log() { }

    public static void setDebug(boolean enabled) { debug = enabled; }

    public static void debug(String msg, Object... kv) { if (debug) write("DEBUG", msg, null, kv); }
    public static void info(String msg, Object... kv) { write("INFO", msg, null, kv); }
    public static void warn(String msg, Throwable t, Object... kv) { write("WARN", msg, t, kv); }
    public static void error(String msg, Throwable t, Object... kv) { write("ERROR", msg, t, kv); }

    private static void write(String level, String msg, Throwable t, Object... kv) {
        try {
            ObjectNode n = JSON.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("level", level);
            n.put("logger", "flairquery-collector");
            n.put("msg", msg);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                n.set(String.valueOf(kv[i]), JSON.valueToTree(kv[i + 1]));
            }
            if (t != null) {
                n.put("error", t.toString());
            }
            System.out.println(JSON.writeValueAsString(n));
        } catch (Exception ignored) {
            // logging never breaks the collector
        }
    }
}
