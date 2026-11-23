package io.github.anthonydeseille.flairquery.agent.orm;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * {@code QueryLogging_$logger#firstOrMaxResultsSpecifiedWithCollectionFetch()} (HHH90003004): Hibernate
 * paginates in memory because the query fetches a collection. Called whatever the log level.
 */
public final class InMemoryPaginationAdvice {

    private InMemoryPaginationAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        Fq.inMemoryPagination();
    }
}
