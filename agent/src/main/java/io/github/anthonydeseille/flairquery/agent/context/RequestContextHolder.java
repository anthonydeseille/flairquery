package io.github.anthonydeseille.flairquery.agent.context;

/**
 * ThreadLocal holder with a depth counter: filters, forwards and error dispatches call
 * {@code service()} several times, only the outermost call opens and closes the context.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() { }

    public static RequestContext current() {
        return CURRENT.get();
    }

    /** @return the context, and whether it was created by this call */
    public static RequestContext enter(Object request, String method, String uri, boolean sampled) {
        RequestContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = new RequestContext(request, method, uri, sampled);
            CURRENT.set(ctx);
        }
        ctx.depth++;
        return ctx;
    }

    /** Opens a nested level without building a new context (avoids reading the request twice). */
    public static boolean enterNested() {
        RequestContext ctx = CURRENT.get();
        if (ctx == null) {
            return false;
        }
        ctx.depth++;
        return true;
    }

    /**
     * Closes one level. Returns the context when the outermost level is closed (it is then removed
     * from the thread), {@code null} otherwise.
     */
    public static RequestContext exit() {
        RequestContext ctx = CURRENT.get();
        if (ctx == null) {
            return null;
        }
        if (--ctx.depth <= 0) {
            CURRENT.remove();
            return ctx;
        }
        return null;
    }

    /** Safety valve (tests, error paths). */
    public static void clear() {
        CURRENT.remove();
    }
}
