package io.github.anthonydeseille.flairquery.agent.jdbc;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the start of {@code Connection#prepareStatement(String, ...)} and
 * {@code Statement#execute*(String, ...)}: replaces the SQL argument by its tagged version.
 * Only a leading comment is added; the statement itself is never rewritten.
 */
public final class SqlArgumentAdvice {

    private SqlArgumentAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 0, readOnly = false) String sql) {
        sql = Fq.tagSql(sql);
    }
}
