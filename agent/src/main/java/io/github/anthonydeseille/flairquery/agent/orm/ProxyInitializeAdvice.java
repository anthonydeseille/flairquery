package io.github.anthonydeseille.flairquery.agent.orm;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/** {@code AbstractLazyInitializer#initialize()}: lazy loading of an entity proxy ({@code LAZY} to-one). */
public final class ProxyInitializeAdvice {

    private ProxyInitializeAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.FieldValue("initialized") boolean initialized,
                             @Advice.FieldValue("entityName") String entityName) {
        return initialized ? -1L : Fq.lazyLoadStart("proxy", entityName);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long start, @Advice.FieldValue("entityName") String entityName,
                            @Advice.Thrown Throwable thrown) {
        Fq.lazyLoadEnd("proxy", entityName, start, thrown);
    }
}
