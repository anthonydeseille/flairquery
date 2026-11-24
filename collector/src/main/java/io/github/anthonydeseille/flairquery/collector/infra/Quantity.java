package io.github.anthonydeseille.flairquery.collector.infra;

import java.math.BigDecimal;

/** Kubernetes resource quantities ({@code 25m}, {@code 1}, {@code 0.5}, {@code 64Mi}, {@code 1G}, {@code 1e3}). */
public final class Quantity {

    private Quantity() { }

    /** CPU in millicores, null when absent. */
    public static Long millicores(String q) {
        BigDecimal v = parse(q);
        return v == null ? null : v.movePointRight(3).setScale(0, java.math.RoundingMode.CEILING).longValueExact();
    }

    /** Memory in bytes, null when absent. */
    public static Long bytes(String q) {
        BigDecimal v = parse(q);
        return v == null ? null : v.setScale(0, java.math.RoundingMode.CEILING).longValueExact();
    }

    static BigDecimal parse(String q) {
        if (q == null || q.isBlank()) return null;
        String s = q.trim();
        String[][] suffixes = {
                {"Ki", "1024"}, {"Mi", "1048576"}, {"Gi", "1073741824"}, {"Ti", "1099511627776"},
                {"Pi", "1125899906842624"}, {"Ei", "1152921504606846976"},
                {"n", "0.000000001"}, {"u", "0.000001"}, {"m", "0.001"},
                {"k", "1000"}, {"M", "1000000"}, {"G", "1000000000"}, {"T", "1000000000000"},
                {"P", "1000000000000000"}, {"E", "1000000000000000000"},
        };
        for (String[] suf : suffixes) {
            if (s.endsWith(suf[0])) {
                return new BigDecimal(s.substring(0, s.length() - suf[0].length())).multiply(new BigDecimal(suf[1]));
            }
        }
        return new BigDecimal(s);   // plain number or exponent (1e3)
    }
}
