package io.github.anthonydeseille.flairquery.collector.plan;

import io.github.anthonydeseille.flairquery.collector.source.LogEntry;
import io.github.anthonydeseille.flairquery.collector.source.StatementRow;
import io.github.anthonydeseille.flairquery.sql.SqlNormalizer;
import io.github.anthonydeseille.flairquery.sql.SqlTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the pg_stat_statements entry of an auto_explain log entry.
 *
 * <p>The {@code query_id} field of the log line cannot be trusted. With the extended query protocol
 * (pgjdbc) inside an explicit transaction, a statement's executor ends, and auto_explain logs its plan,
 * only when its portal is closed: at the next statement or at COMMIT. The query_id reported at that moment
 * is the one of that next statement (observed on PostgreSQL 16). Text, tag, plan and duration of the
 * entry are its own. So the entry is matched on its normalized text (tag removed, literals and parameters
 * as {@code ?}) against the pg_stat_statements texts of the same database and role; the logged query_id
 * only breaks ties.
 */
public final class QueryIdResolver {

    /** No pg_stat_statements entry has this text (evicted, or normalization differs). */
    public static final long UNMATCHED = 0L;

    private record Key(String db, String role, String normalized) { }

    private record Candidate(long queryId, boolean toplevel, double calls) { }

    private final Map<Key, List<Candidate>> byText = new HashMap<>();
    private final boolean trustLog;
    private final Map<String, String> normalizedCache = new HashMap<>();

    public QueryIdResolver(Collection<StatementRow> statements) {
        this(statements, false);
    }

    private QueryIdResolver(Collection<StatementRow> statements, boolean trustLog) {
        this.trustLog = trustLog;
        for (StatementRow r : statements) {
            if (r.query() == null) continue;
            byText.computeIfAbsent(new Key(r.dbName(), r.roleName(), normalize(r.query())), k -> new ArrayList<>())
                    .add(new Candidate(r.key().queryid(), r.key().toplevel(), r.counters().get(0)));
        }
    }

    /** Uses the logged query_id as is: only correct in autocommit mode (kept for tests and comparisons). */
    public static QueryIdResolver trustingTheLog() {
        return new QueryIdResolver(List.of(), true);
    }

    public long resolve(LogEntry e) {
        if (trustLog) return e.queryId();
        List<Candidate> candidates = byText.get(new Key(e.dbName(), e.roleName(), normalize(e.queryText())));
        if (candidates == null || candidates.isEmpty()) return UNMATCHED;
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c.queryId() == e.queryId()) return c.queryId();
            if (best == null || (c.toplevel() && !best.toplevel())
                    || (c.toplevel() == best.toplevel() && c.calls() > best.calls())) {
                best = c;
            }
        }
        return best.queryId();
    }

    private String normalize(String sql) {
        return normalizedCache.computeIfAbsent(sql, s -> SqlNormalizer.normalize(SqlTag.strip(s)));
    }
}
