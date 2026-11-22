package io.github.anthonydeseille.flairquery.agent.bootstrap;

/**
 * Operations invoked from code inlined into instrumented classes.
 * Implementations may throw: {@link Fq} guarantees nothing reaches the application.
 */
public interface FqHooks {

    /** Returns the SQL to send to the driver (tagged, or unchanged). */
    String tagSql(String sql);

    /** A {@code PreparedStatement} was created for {@code sql} (already tagged). */
    void statementPrepared(Object statement, String sql);

    /**
     * Start of a statement execution. Returns a start timestamp, or {@code -1} when this execution must
     * not be measured (nested call: pool proxy delegating to the driver).
     */
    long queryStart();

    /** End of an execution started by {@link #queryStart()}; {@code sql} is null for prepared statements. */
    void queryEnd(Object statement, String sql, long start, Throwable thrown);

    void requestEnter(Object servletRequest);

    void requestExit(Object servletRequest, Object servletResponse, Throwable thrown);

    FqHooks NOOP = new FqHooks() {
        @Override public String tagSql(String sql) { return sql; }
        @Override public void statementPrepared(Object statement, String sql) { }
        @Override public long queryStart() { return -1L; }
        @Override public void queryEnd(Object statement, String sql, long start, Throwable thrown) { }
        @Override public void requestEnter(Object servletRequest) { }
        @Override public void requestExit(Object servletRequest, Object servletResponse, Throwable thrown) { }
    };
}
