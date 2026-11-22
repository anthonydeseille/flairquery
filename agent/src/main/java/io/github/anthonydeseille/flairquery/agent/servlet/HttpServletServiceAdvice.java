package io.github.anthonydeseille.flairquery.agent.servlet;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code HttpServlet#service(HttpServletRequest, HttpServletResponse)} and its overrides.
 * The exit advice runs on normal and exceptional completion, so the request context is always cleaned.
 */
public final class HttpServletServiceAdvice {

    private HttpServletServiceAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Object request) {
        Fq.requestEnter(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Argument(0) Object request,
                            @Advice.Argument(1) Object response,
                            @Advice.Thrown Throwable thrown) {
        Fq.requestExit(request, response, thrown);
    }
}
