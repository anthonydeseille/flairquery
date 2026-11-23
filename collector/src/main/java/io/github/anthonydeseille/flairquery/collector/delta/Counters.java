package io.github.anthonydeseille.flairquery.collector.delta;

import java.util.Arrays;

/**
 * A fixed-size vector of cumulative counters. Index 0 is the "activity" counter (calls for statements,
 * seq_scan for tables); doubles are used so time totals and integer counters share the same arithmetic.
 */
public record Counters(double[] values) {

    public static Counters of(double... values) {
        return new Counters(values.clone());
    }

    public double get(int i) {
        return values[i];
    }

    public int size() {
        return values.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Counters c && Arrays.equals(values, c.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
