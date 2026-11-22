package io.github.anthonydeseille.flairquery.agent.log;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/** Structured one-line JSON logs on stdout, without any logging library. */
public final class JsonLog {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level threshold = Level.INFO;
    private static volatile PrintStream out = System.out;

    private JsonLog() { }

    public static void setThreshold(Level level) {
        threshold = level;
    }

    /** Test hook. */
    public static void setOut(PrintStream stream) {
        out = stream;
    }

    public static void debug(String msg, Object... kv) { log(Level.DEBUG, msg, null, kv); }
    public static void info(String msg, Object... kv) { log(Level.INFO, msg, null, kv); }
    public static void warn(String msg, Throwable t, Object... kv) { log(Level.WARN, msg, t, kv); }
    public static void error(String msg, Throwable t, Object... kv) { log(Level.ERROR, msg, t, kv); }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void log(Level level, String msg, Throwable t, Object... kv) {
        if (!isEnabled(level)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"ts\":\"").append(Instant.now()).append("\",\"level\":\"").append(level)
              .append("\",\"logger\":\"flairquery-agent\",\"msg\":");
            Json.string(sb, msg);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(',');
                Json.string(sb, String.valueOf(kv[i]));
                sb.append(':');
                Json.value(sb, kv[i + 1]);
            }
            if (t != null) {
                sb.append(",\"error\":");
                Json.string(sb, t.toString());
                if (isEnabled(Level.DEBUG)) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    sb.append(",\"stack\":");
                    Json.string(sb, sw.toString());
                }
            }
            sb.append('}');
            out.println(sb);
        } catch (Throwable ignored) {
            // never propagate
        }
    }
}
