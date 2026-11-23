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

    public static void statementPrepared(Object statement, String sql) {
        if (statement == null || sql == null) {
            return;
        }
        try {
            hooks.statementPrepared(statement, sql);
        } catch (Throwable t) {
            onError("statementPrepared", t);
        }
    }

    public static long queryStart() {
        try {
            return hooks.queryStart();
        } catch (Throwable t) {
            onError("queryStart", t);
            return -1L;
        }
    }

    public static void queryEnd(Object statement, String sql, long start, Throwable thrown) {
        try {
            hooks.queryEnd(statement, sql, start, thrown);
        } catch (Throwable t) {
            onError("queryEnd", t);
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

    // ---------------------------------------------------------------- JPA profile (phase 3)

    public static void queryEnd(Object statement, String sql, long start, Throwable thrown, Object result) {
        try {
            hooks.queryEnd(statement, sql, start, thrown, result);
        } catch (Throwable t) {
            onError("queryEnd", t);
        }
    }

    public static void rowRead(Object resultSet) {
        try {
            hooks.rowRead(resultSet);
        } catch (Throwable t) {
            onError("rowRead", t);
        }
    }


    public static void entityLoaded(Object entity) {
        if (entity == null) {
            return;
        }
        try {
            hooks.entityLoaded(entity);
        } catch (Throwable t) {
            onError("entityLoaded", t);
        }
    }

    public static long lazyLoadStart(String kind, String role) {
        try {
            return hooks.lazyLoadStart(kind, role);
        } catch (Throwable t) {
            onError("lazyLoadStart", t);
            return -1L;
        }
    }

    public static void lazyLoadEnd(String kind, String role, long start, Throwable thrown) {
        if (start < 0) {
            return;
        }
        try {
            hooks.lazyLoadEnd(kind, role, start, thrown);
        } catch (Throwable t) {
            onError("lazyLoadEnd", t);
        }
    }

    public static void inMemoryPagination() {
        try {
            hooks.inMemoryPagination();
        } catch (Throwable t) {
            onError("inMemoryPagination", t);
        }
    }

    public static void transactionEnter() {
        try {
            hooks.transactionEnter();
        } catch (Throwable t) {
            onError("transactionEnter", t);
        }
    }

    public static void transactionExit() {
        try {
            hooks.transactionExit();
        } catch (Throwable t) {
            onError("transactionExit", t);
        }
    }

    public static void openInView() {
        try {
            hooks.openInView();
        } catch (Throwable t) {
            onError("openInView", t);
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
