package io.github.anthonydeseille.flairquery.analyzer.size;

/** Rounding and formatting of the recommended values (Kubernetes quantities, PostgreSQL memory settings). */
final class Units {

    static final long MI = 1024L * 1024;

    private Units() { }

    static long roundUp(long v, long step) {
        return v <= 0 ? step : ((v + step - 1) / step) * step;
    }

    static long roundDown(long v, long step) {
        return Math.max(step, (v / step) * step);
    }

    /** Largest power of two (in MiB) not above {@code bytes}, at least 1 MiB. */
    static long floorPow2Mi(long bytes) {
        long mi = Math.max(1, bytes / MI);
        return Long.highestOneBit(mi) * MI;
    }

    /** Smallest power of two (in MiB) not below {@code bytes}, at least 1 MiB. */
    static long ceilPow2Mi(long bytes) {
        long mi = Math.max(1, (bytes + MI - 1) / MI);
        long p = Long.highestOneBit(mi);
        return (p == mi ? p : p << 1) * MI;
    }

    /** "250m", "1", "1500m". */
    static String cpu(long millis) {
        return millis % 1000 == 0 ? Long.toString(millis / 1000) : millis + "m";
    }

    /** "512Mi", "1Gi" (whole MiB). */
    static String memory(long bytes) {
        long mi = Math.max(1, Math.round(bytes / (double) MI));
        return mi % 1024 == 0 ? (mi / 1024) + "Gi" : mi + "Mi";
    }

    /** PostgreSQL memory setting: "16MB", "1GB", "64kB". */
    static String pg(long bytes) {
        if (bytes < MI) return Math.max(1, bytes / 1024) + "kB";
        long mb = bytes / MI;
        return mb % 1024 == 0 ? (mb / 1024) + "GB" : mb + "MB";
    }

    /** Human figure for the evidence: "614 MiB". */
    static String mib(double bytes) {
        if (bytes > 0 && bytes < MI) return Math.max(1, Math.round(bytes / 1024)) + " KiB";
        return Math.round(bytes / MI) + " MiB";
    }
}
