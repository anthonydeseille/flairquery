package io.github.anthonydeseille.flairquery.collector.store;

/** Derived values of pg_stats, computed from statistics only (never from data). */
public final class ColumnStatsMath {

    private ColumnStatsMath() { }

    /** Estimated number of distinct values: n_distinct when positive, else -n_distinct × rows. */
    public static Double distinct(Double nDistinct, Double rows) {
        if (nDistinct == null) return null;
        if (nDistinct > 0) return nDistinct;
        if (rows == null || rows < 0) return null;   // reltuples = -1: never vacuumed / analyzed
        return -nDistinct * rows;
    }

    /** Rows matched by an equality on one non-null value (what the planner estimates), or null if unknown. */
    public static Double rowsPerValue(Double nDistinct, Double nullFrac, Double rows) {
        Double d = distinct(nDistinct, rows);
        if (d == null || d <= 0 || rows == null || rows < 0) return null;
        double nonNull = rows * (1 - (nullFrac == null ? 0 : nullFrac));
        return nonNull / d;
    }
}
