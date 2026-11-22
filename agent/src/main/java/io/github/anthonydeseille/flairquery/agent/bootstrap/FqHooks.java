package io.github.anthonydeseille.flairquery.agent.bootstrap;

/**
 * Operations invoked from code inlined into instrumented classes.
 * Implementations may throw: {@link Fq} guarantees nothing reaches the application.
 */
public interface FqHooks {

    /** Returns the SQL to send to the driver (tagged, or unchanged). */
    String tagSql(String sql);

    void requestEnter(Object servletRequest);

    void requestExit(Object servletRequest, Object servletResponse, Throwable thrown);

    FqHooks NOOP = new FqHooks() {
        @Override public String tagSql(String sql) { return sql; }
        @Override public void requestEnter(Object servletRequest) { }
        @Override public void requestExit(Object servletRequest, Object servletResponse, Throwable thrown) { }
    };
}
