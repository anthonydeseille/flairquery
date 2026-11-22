package io.github.anthonydeseille.flairquery.agent.jdbc;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weak, identity-based map statement → SQL. Statements are never retained by the agent: entries vanish
 * when the application drops the statement. Bounded as a last resort.
 */
public final class StatementSqlRegistry {

    static final int MAX_ENTRIES = 100_000;

    private final ConcurrentHashMap<Object, String> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    public void put(Object statement, String sql) {
        expunge();
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
        map.put(new WeakKey(statement, queue), sql);
    }

    public String get(Object statement) {
        return map.get(new LookupKey(statement));
    }

    public int size() {
        expunge();
        return map.size();
    }

    private void expunge() {
        Object ref;
        while ((ref = queue.poll()) != null) {
            map.remove(ref);
        }
    }

    private static final class WeakKey extends WeakReference<Object> {
        private final int hash;

        WeakKey(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }

        @Override public int hashCode() { return hash; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            Object mine = get();
            if (mine == null) return false;
            if (o instanceof WeakKey w) return mine == w.get();
            if (o instanceof LookupKey l) return mine == l.referent;
            return false;
        }
    }

    private record LookupKey(Object referent) {
        @Override public int hashCode() { return System.identityHashCode(referent); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof WeakKey w) return referent == w.get();
            if (o instanceof LookupKey l) return referent == l.referent;
            return false;
        }
    }
}
