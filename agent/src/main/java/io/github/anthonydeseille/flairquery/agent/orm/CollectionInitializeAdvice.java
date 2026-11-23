package io.github.anthonydeseille.flairquery.agent.orm;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * {@code AbstractPersistentCollection#initialize(boolean)}: lazy loading of a collection. The method returns
 * immediately when the collection is already initialized: only real loads are measured.
 */
public final class CollectionInitializeAdvice {

    private CollectionInitializeAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.FieldValue("initialized") boolean initialized,
                             @Advice.FieldValue("role") String role) {
        return initialized ? -1L : Fq.lazyLoadStart("collection", role);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long start, @Advice.FieldValue("role") String role,
                            @Advice.Thrown Throwable thrown) {
        Fq.lazyLoadEnd("collection", role, start, thrown);
    }
}
