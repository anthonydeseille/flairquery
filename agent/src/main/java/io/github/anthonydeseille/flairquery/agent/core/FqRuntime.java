package io.github.anthonydeseille.flairquery.agent.core;

import io.github.anthonydeseille.flairquery.agent.FqConfig;
import io.github.anthonydeseille.flairquery.agent.bootstrap.FqHooks;
import io.github.anthonydeseille.flairquery.agent.caller.CallerResolver;
import io.github.anthonydeseille.flairquery.agent.context.RequestContext;
import io.github.anthonydeseille.flairquery.agent.context.RequestContextHolder;
import io.github.anthonydeseille.flairquery.agent.servlet.ServletRequestAccessor;
import io.github.anthonydeseille.flairquery.agent.tag.SqlTag;

import java.util.concurrent.ThreadLocalRandom;

/** The live implementation behind {@link io.github.anthonydeseille.flairquery.agent.bootstrap.Fq}. */
public final class FqRuntime implements FqHooks {

    /** Endpoint value for statements executed outside an HTTP request (startup, schedulers, ...). */
    public static final String NO_ENDPOINT = "none";

    private final FqConfig config;
    private final CallerResolver callerResolver;

    public FqRuntime(FqConfig config) {
        this.config = config;
        this.callerResolver = new CallerResolver(config.basePackages());
    }

    public FqConfig config() {
        return config;
    }

    @Override
    public String tagSql(String sql) {
        if (SqlTag.isTagged(sql)) {
            return sql; // idempotent: pool proxy (Hikari) then driver both see the statement
        }
        if (config.fault("tagger")) {
            throw new IllegalStateException("FQ_TEST_FAULT=tagger");
        }
        RequestContext ctx = RequestContextHolder.current();
        String endpoint;
        if (ctx == null) {
            endpoint = NO_ENDPOINT;
        } else if (!ctx.sampled()) {
            return sql;
        } else {
            endpoint = endpoint(ctx);
        }
        String caller = config.fault("caller") ? fail("caller") : callerResolver.resolve();
        return SqlTag.prefix(config.appName(), endpoint, caller) + sql;
    }

    @Override
    public void requestEnter(Object request) {
        if (RequestContextHolder.enterNested()) {
            return;
        }
        if (config.fault("request")) {
            throw new IllegalStateException("FQ_TEST_FAULT=request");
        }
        String method = ServletRequestAccessor.method(request);
        String uri = ServletRequestAccessor.uri(request);
        double rate = config.samplingRate();
        boolean sampled = rate >= 1.0 || (rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate);
        RequestContextHolder.enter(request, method, uri, sampled);
    }

    @Override
    public void requestExit(Object request, Object response, Throwable thrown) {
        RequestContextHolder.exit();
    }

    /**
     * {@code METHOD pattern} from Spring's best matching pattern once the handler is mapped
     * (it is set after {@code service()} starts, so it is read lazily), raw URI before that.
     */
    String endpoint(RequestContext ctx) {
        String resolved = ctx.resolvedEndpoint();
        if (resolved != null) {
            return resolved;
        }
        String method = ctx.method() == null ? "" : ctx.method() + " ";
        String pattern = ServletRequestAccessor.bestMatchingPattern(ctx.request());
        if (pattern != null) {
            resolved = SqlTag.sanitize(method + pattern);
            ctx.resolvedEndpoint(resolved);
            return resolved;
        }
        String fallback = ctx.fallbackEndpoint();
        if (fallback == null) {
            fallback = SqlTag.sanitize(method + (ctx.uri() == null ? SqlTag.UNKNOWN : ctx.uri()));
            ctx.fallbackEndpoint(fallback);
        }
        return fallback;
    }

    private static String fail(String what) {
        throw new IllegalStateException("FQ_TEST_FAULT=" + what);
    }
}
