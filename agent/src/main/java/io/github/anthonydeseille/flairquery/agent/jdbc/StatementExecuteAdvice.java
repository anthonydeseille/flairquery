package io.github.anthonydeseille.flairquery.agent.jdbc;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/** {@code Statement#execute / executeQuery / executeUpdate / executeLargeUpdate(String, ...)}: tag + measure. */
public final class StatementExecuteAdvice {

    private StatementExecuteAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.Argument(value = 0, readOnly = false) String sql) {
        sql = Fq.tagSql(sql);
        return Fq.queryStart();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.This Object statement,
                            @Advice.Argument(0) String sql,
                            @Advice.Enter long start,
                            @Advice.Thrown Throwable thrown) {
        Fq.queryEnd(statement, sql, start, thrown);
    }
}
