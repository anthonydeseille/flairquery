package io.github.anthonydeseille.flairquery.agent.bootstrap;

import io.github.anthonydeseille.flairquery.agent.log.JsonLog;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static entry point called by advice code inlined into application classes.
 *
 * <p>Fail-safe contract: every method catches {@link Throwable}, never throws, and on error
 * returns the neutral value (the original SQL). After {@link #MAX_ERRORS} internal errors the
 * agent switches itself to no-op for the rest of the JVM lifetime (circuit breaker).
 */
public final class Fq {

    public static final int MAX_ERRORS = 100;

    private static volatile FqHooks hooks = FqHooks.NOOP;
    private static final AtomicInteger ERRORS = new AtomicInteger();

    private Fq() { }

    public static void install(FqHooks newHooks) {
        ERRORS.set(0);
        hooks = newHooks == null ? FqHooks.NOOP : newHooks;
    }

    public static void reset() {
        hooks = FqHooks.NOOP;
    }

    public static boolean isActive() {
        return hooks != FqHooks.NOOP;
    }

    public static int errorCount() {
        return ERRORS.get();
    }

    public static String tagSql(String sql) {
        if (sql == null) {
            return null;
        }
        try {
            String tagged = hooks.tagSql(sql);
            return tagged == null ? sql : tagged;
        } catch (Throwable t) {
            onError("tagSql", t);
            return sql;
        }
    }

    public static void requestEnter(Object servletRequest) {
        try {
            hooks.requestEnter(servletRequest);
        } catch (Throwable t) {
            onError("requestEnter", t);
        }
    }

    public static void requestExit(Object servletRequest, Object servletResponse, Throwable thrown) {
        try {
            hooks.requestExit(servletRequest, servletResponse, thrown);
        } catch (Throwable t) {
            onError("requestExit", t);
        }
    }

    private static void onError(String operation, Throwable t) {
        try {
            int n = ERRORS.incrementAndGet();
            if (n == 1 || n % 10 == 0) {
                JsonLog.warn("internal error, application unaffected", t, "operation", operation, "errorCount", n);
            }
            if (n == MAX_ERRORS) {
                hooks = FqHooks.NOOP;
                JsonLog.error("too many internal errors, instrumentation disabled until restart", null,
                        "errorCount", n);
            }
        } catch (Throwable ignored) {
            // logging must never break the application either
        }
    }
}
