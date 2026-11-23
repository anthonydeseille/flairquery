package io.github.anthonydeseille.flairquery.agent.jdbc;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * {@code ResultSet#next()}: one row read. Pool proxies and drivers are both instrumented; only the result set
 * returned by the outermost (measured) execution is registered, so a row is counted once.
 */
public final class ResultSetNextAdvice {

    private ResultSetNextAdvice() { }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object resultSet, @Advice.Return boolean hasRow) {
        if (hasRow) {
            Fq.rowRead(resultSet);
        }
    }
}
