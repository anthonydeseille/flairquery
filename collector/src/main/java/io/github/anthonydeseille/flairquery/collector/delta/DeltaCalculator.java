package io.github.anthonydeseille.flairquery.collector.delta;

/**
 * Turns two cumulative readings into a delta, without ever producing negative or invented values.
 *
 * <ul>
 *   <li>no previous reading (new entry, or re-created after an eviction): the whole value is the delta;</li>
 *   <li>global reset observed (pg_stat_statements_info.stats_reset changed): the whole value is the delta;</li>
 *   <li>any counter went backwards (entry evicted and re-created, table stats reset): the whole value;</li>
 *   <li>otherwise: current minus previous.</li>
 * </ul>
 * A delta without activity (every component zero) is reported as {@code null}.
 */
public final class DeltaCalculator {

    private DeltaCalculator() { }

    public record Delta(Counters values, boolean restarted) {

        public double get(int i) {
            return values.get(i);
        }
    }

    public static Delta compute(Counters previous, Counters current, boolean resetObserved) {
        if (previous == null || resetObserved || wentBackwards(previous, current)) {
            return isZero(current) ? null : new Delta(current, true);
        }
        double[] d = new double[current.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = current.get(i) - previous.get(i);
        }
        Counters delta = new Counters(d);
        return isZero(delta) ? null : new Delta(delta, false);
    }

    static boolean wentBackwards(Counters previous, Counters current) {
        if (previous.size() != current.size()) {
            return true;
        }
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i) < previous.get(i)) {
                return true;
            }
        }
        return false;
    }

    static boolean isZero(Counters c) {
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i) != 0) {
                return false;
            }
        }
        return true;
    }
}
