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

    // ---------------------------------------------------------------- JPA profile (phase 3)

    /** {@link #queryEnd(Object, String, long, Throwable)} with the value returned by the execute method. */
    default void queryEnd(Object statement, String sql, long start, Throwable thrown, Object result) {
        queryEnd(statement, sql, start, thrown);
    }

    /** {@code ResultSet#next()} returned true on {@code resultSet}. */
    default void rowRead(Object resultSet) { }

    /** A getter of an {@code @Entity} class was called on {@code entity}. */
    default void attributeRead(Object entity, String getter) { }


    /** Hibernate put an entity into the persistence context (loaded from a result set). */
    default void entityLoaded(Object entity) { }

    /**
     * A lazy collection or proxy starts loading ({@code kind} = "collection" | "proxy", {@code role} = collection
     * role or entity name). Returns a start timestamp, or {@code -1} when not measured.
     */
    default long lazyLoadStart(String kind, String role) { return -1L; }

    default void lazyLoadEnd(String kind, String role, long start, Throwable thrown) { }

    /** Hibernate applied first/max results in memory because of a collection fetch (HHH90003004). */
    default void inMemoryPagination() { }

    /** Entering / leaving a Spring-managed transaction boundary ({@code @Transactional}, TransactionTemplate). */
    default void transactionEnter() { }

    default void transactionExit() { }

    /** Spring's OpenEntityManagerInViewInterceptor is active for the current request. */
    default void openInView() { }

    FqHooks NOOP = new FqHooks() {
        @Override public String tagSql(String sql) { return sql; }
        @Override public void statementPrepared(Object statement, String sql) { }
        @Override public long queryStart() { return -1L; }
        @Override public void queryEnd(Object statement, String sql, long start, Throwable thrown) { }
        @Override public void requestEnter(Object servletRequest) { }
        @Override public void requestExit(Object servletRequest, Object servletResponse, Throwable thrown) { }
    };
}
