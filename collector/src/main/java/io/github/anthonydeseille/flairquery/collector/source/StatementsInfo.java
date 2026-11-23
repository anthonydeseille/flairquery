package io.github.anthonydeseille.flairquery.collector.source;

import java.time.Instant;

/** pg_stat_statements_info: global reset time and number of evicted entries. */
public record StatementsInfo(Instant statsReset, long dealloc) { }
