package io.github.anthonydeseille.flairquery.agent.spring;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/** {@code OpenEntityManagerInViewInterceptor#preHandle}: open-in-view is active for this request. */
public final class OpenInViewAdvice {

    private OpenInViewAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        Fq.openInView();
    }
}
