package io.github.anthonydeseille.flairquery.agent.jdbc;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * {@code Connection#prepareStatement(String, ...)}: tags the SQL on entry, remembers which SQL the
 * returned statement runs on exit (needed to measure {@code PreparedStatement#execute()}).
 */
public final class PrepareStatementAdvice {

    private PrepareStatementAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 0, readOnly = false) String sql) {
        sql = Fq.tagSql(sql);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Argument(0) String sql, @Advice.Return Object statement) {
        Fq.statementPrepared(statement, sql);
    }
}
