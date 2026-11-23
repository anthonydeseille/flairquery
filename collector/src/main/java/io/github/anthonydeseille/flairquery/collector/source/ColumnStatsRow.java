package io.github.anthonydeseille.flairquery.collector.source;

/**
 * Value-free planner statistics of one column (pg_stats). Nulls when the column was never analyzed.
 * {@code nDistinct} > 0 is a count of distinct values, < 0 a fraction of the rows (-1 = unique).
 */
public record ColumnStatsRow(String dbName, String schema, String table, String column, Float nDistinct,
                             Float nullFrac, Float correlation, Integer avgWidth, Float reltuples) { }
