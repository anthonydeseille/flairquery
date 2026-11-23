package io.github.anthonydeseille.flairquery.collector.source;

import io.github.anthonydeseille.flairquery.collector.delta.Counters;

/** One pg_stat_user_tables row. Counters: seq_scan, seq_tup_read, idx_scan, idx_tup_fetch, ins, upd, del. */
public record TableRow(String dbName, long relid, String schema, String table, Counters counters, long liveTuples) { }
