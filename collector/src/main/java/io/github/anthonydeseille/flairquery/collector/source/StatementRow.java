package io.github.anthonydeseille.flairquery.collector.source;

import io.github.anthonydeseille.flairquery.collector.delta.Counters;

/** One pg_stat_statements entry (cumulative counters since its creation or the last reset). */
public record StatementRow(StatementKey key, String dbName, String roleName, String query,
                           Counters counters, double maxExecTime) { }
