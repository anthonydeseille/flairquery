package io.github.anthonydeseille.flairquery.agent.spi;

/**
 * Extension point for later phases (row counts, entities loaded vs used, exporters...).
 * Listeners are called synchronously on the application thread: they must be fast; their exceptions
 * are caught by the fail-safe facade.
 */
public interface FqListener {

    /** A statement executed outside any HTTP request (startup, schedulers, ...). */
    default void onBackgroundQuery(String app, String normalizedSql, String caller, long nanos, boolean failed) { }

    /** An HTTP request completed. */
    default void onRequest(RequestSummary summary) { }
}
