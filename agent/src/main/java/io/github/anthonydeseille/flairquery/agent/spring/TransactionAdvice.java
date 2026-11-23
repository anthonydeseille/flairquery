package io.github.anthonydeseille.flairquery.agent.spring;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * {@code TransactionAspectSupport#invokeWithinTransaction} ({@code @Transactional}) and
 * {@code TransactionTemplate#execute}: tracks whether the current request runs inside a Spring transaction.
 */
public final class TransactionAdvice {

    private TransactionAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        Fq.transactionEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
        Fq.transactionExit();
    }
}
