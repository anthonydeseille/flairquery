package io.github.anthonydeseille.flairquery.collector.plan;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.collector.source.LogEntry;
import io.github.anthonydeseille.flairquery.sql.SqlTag;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Aggregates auto_explain entries of one cycle: executions per (statement, endpoint, caller), plan shapes. */
public final class Attribution {

    private Attribution() { }

    public record Key(String dbName, String roleName, long queryId, String app, String endpoint, String caller) { }

    public static final class Executions {
        public long count;
        public double totalMs;
        public double maxMs;
    }

    public record PlanKey(String dbName, long queryId, String fingerprint) { }

    public static final class PlanObservation {
        public long count;
        public JsonNode plan;
        public JsonNode summary;
    }

    public record Result(Map<Key, Executions> executions, Map<PlanKey, PlanObservation> plans) { }

    /** Trusts the logged query_id: only correct in autocommit mode, see {@link QueryIdResolver}. */
    public static Result of(Collection<LogEntry> entries, String excludedRole) {
        return of(entries, excludedRole, QueryIdResolver.trustingTheLog());
    }

    /**
     * Entries of {@code excludedRole} (the collector itself) are ignored. The statement of each entry is found
     * by {@code resolver}; entries without a statement keep their endpoint (queryid {@code 0}), no plan.
     */
    public static Result of(Collection<LogEntry> entries, String excludedRole, QueryIdResolver resolver) {
        Map<Key, Executions> executions = new LinkedHashMap<>();
        Map<PlanKey, PlanObservation> plans = new LinkedHashMap<>();
        for (LogEntry e : entries) {
            if (e.roleName().equals(excludedRole)) {
                continue;
            }
            long queryId = resolver.resolve(e);
            Optional<SqlTag.FqTag> tag = SqlTag.parse(e.queryText());
            Key key = new Key(e.dbName(), e.roleName(), queryId,
                    tag.map(SqlTag.FqTag::app).orElse(null),
                    tag.map(SqlTag.FqTag::endpoint).orElse(null),
                    tag.map(SqlTag.FqTag::caller).orElse(null));
            Executions x = executions.computeIfAbsent(key, k -> new Executions());
            x.count++;
            x.totalMs += e.durationMs();
            x.maxMs = Math.max(x.maxMs, e.durationMs());
            if (queryId != QueryIdResolver.UNMATCHED && !e.plan().isMissingNode()) {
                PlanKey pk = new PlanKey(e.dbName(), queryId, PlanAnalyzer.fingerprint(e.plan()));
                PlanObservation p = plans.computeIfAbsent(pk, k -> {
                    PlanObservation o = new PlanObservation();
                    o.plan = e.plan();
                    o.summary = PlanAnalyzer.summary(e.plan());
                    return o;
                });
                p.count++;
            }
        }
        return new Result(executions, plans);
    }
}
