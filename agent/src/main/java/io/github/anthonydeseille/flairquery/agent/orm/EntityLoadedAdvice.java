package io.github.anthonydeseille.flairquery.agent.orm;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/** {@code StatefulPersistenceContext#addEntity(EntityKey, Object)}: an entity enters the persistence context. */
public final class EntityLoadedAdvice {

    private EntityLoadedAdvice() { }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Argument(1) Object entity) {
        Fq.entityLoaded(entity);
    }
}
