package io.github.anthonydeseille.flairquery.agent.orm;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * Getters of the application's {@code @Entity} classes: which persistent attributes are really read
 * (application code, DTO mappers, Jackson). The method name is a constant of the instrumented method.
 */
public final class EntityGetterAdvice {

    private EntityGetterAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object entity, @Advice.Origin("#m") String getter) {
        Fq.attributeRead(entity, getter);
    }
}
