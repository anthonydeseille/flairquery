package io.github.anthonydeseille.flairquery.collector;

import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.anthonydeseille.flairquery.collector.delta.Counters;
import io.github.anthonydeseille.flairquery.collector.plan.Attribution;
import io.github.anthonydeseille.flairquery.collector.plan.QueryIdResolver;
import io.github.anthonydeseille.flairquery.collector.source.LogEntry;
import io.github.anthonydeseille.flairquery.collector.source.StatementKey;
import io.github.anthonydeseille.flairquery.collector.source.StatementRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryIdResolverTest {

    static final long SEARCH = 385L;
    static final long COMMIT = 206L;

    static StatementRow pss(long queryid, boolean toplevel, long calls, String text) {
        return new StatementRow(new StatementKey(1, 10, queryid, toplevel), "flairquery", "flairquery", text,
                Counters.of(calls, 1.0, 0, 0, 0, 0, 0), 1.0);
    }

    static LogEntry logged(long loggedQueryId, String text) {
        return new LogEntry(Instant.EPOCH, "flairquery", "flairquery", loggedQueryId, 3.0, text, MissingNode.getInstance());
    }

    final QueryIdResolver resolver = new QueryIdResolver(List.of(
            pss(SEARCH, true, 5, "/* fq:app=demo,endpoint=GET /books,caller=X#a:1 */ select r1_0.id from review r1_0 where r1_0.reviewer_name=$1"),
            pss(COMMIT, true, 50, "COMMIT")));

    @Test
    void textWinsOverTheStaleQueryIdOfTheNextStatement() {
        // logged at COMMIT time (portal closed by the commit): query_id is COMMIT's, text and tag are the search's
        LogEntry e = logged(COMMIT,
                "/* fq:app=demo,endpoint=GET /reviews/search,caller=X#search:9 */ select r1_0.id from review r1_0 where r1_0.reviewer_name=$1");
        assertThat(resolver.resolve(e)).isEqualTo(SEARCH);

        Attribution.Result r = Attribution.of(List.of(e), "fq_reader", resolver);
        assertThat(r.executions().keySet()).singleElement()
                .satisfies(k -> {
                    assertThat(k.queryId()).isEqualTo(SEARCH);
                    assertThat(k.endpoint()).isEqualTo("GET /reviews/search");
                });
    }

    @Test
    void literalsAndWhitespaceDoNotMatter() {
        LogEntry e = logged(COMMIT, "select r1_0.id  from review r1_0\n where r1_0.reviewer_name='Alice Martin 00000'");
        assertThat(resolver.resolve(e)).isEqualTo(SEARCH);
    }

    @Test
    void unknownTextIsUnmatchedAndKeepsNoPlan() {
        LogEntry e = logged(COMMIT, "/* fq:app=demo,endpoint=GET /x,caller=X#x:1 */ select 1 from book");
        assertThat(resolver.resolve(e)).isEqualTo(QueryIdResolver.UNMATCHED);
        Attribution.Result r = Attribution.of(List.of(e), "fq_reader", resolver);
        assertThat(r.executions().keySet()).singleElement().satisfies(k -> assertThat(k.endpoint()).isEqualTo("GET /x"));
        assertThat(r.plans()).isEmpty();
    }

    @Test
    void loggedQueryIdBreaksTiesBetweenIdenticalTexts() {
        QueryIdResolver twoEntries = new QueryIdResolver(List.of(
                pss(1L, true, 100, "select 1 from author where id = $1"),
                pss(2L, false, 5, "select 1 from author where id = $1")));
        assertThat(twoEntries.resolve(logged(2L, "select 1 from author where id = $1"))).isEqualTo(2L);
        assertThat(twoEntries.resolve(logged(99L, "select 1 from author where id = $1"))).isEqualTo(1L); // top-level
    }
}
