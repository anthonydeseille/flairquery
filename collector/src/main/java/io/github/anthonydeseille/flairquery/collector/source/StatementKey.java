package io.github.anthonydeseille.flairquery.collector.source;

/** Same key as pg_stat_statements: (userid, dbid, queryid, toplevel). */
public record StatementKey(long dbid, long userid, long queryid, boolean toplevel) { }
