package io.github.anthonydeseille.flairquery.agent.spi;

import io.github.anthonydeseille.flairquery.agent.metrics.RequestStats;

/**
 * One completed HTTP request, as seen by the agent.
 *
 * @param endpoint   final endpoint (Spring pattern when known)
 * @param status     HTTP status, or -1 when unknown
 * @param durationNanos wall-clock time of the outermost {@code service()} call
 * @param stats      SQL executed while serving it (read-only for listeners)
 * @param nPlusOne   normalized SQL statements whose count exceeded the threshold
 */
public record RequestSummary(String app, String endpoint, int status, long durationNanos,
                             RequestStats stats, java.util.List<String> nPlusOne) { }
