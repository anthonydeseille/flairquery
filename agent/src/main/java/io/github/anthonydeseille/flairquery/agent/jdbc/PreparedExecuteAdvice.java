package io.github.anthonydeseille.flairquery.agent.jdbc;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/** No-argument {@code execute / executeQuery / executeUpdate / executeLargeUpdate / executeBatch}: measure. */
public final class PreparedExecuteAdvice {

    private PreparedExecuteAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter() {
        return Fq.queryStart();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.This Object statement,
                            @Advice.Enter long start,
                            @Advice.Thrown Throwable thrown) {
        Fq.queryEnd(statement, null, start, thrown);
    }
}
