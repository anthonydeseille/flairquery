package io.github.anthonydeseille.flairquery.agent.context;

import io.github.anthonydeseille.flairquery.agent.metrics.RequestStats;

/**
 * State of the HTTP request being served by the current thread. Created by the outermost
 * {@code HttpServlet#service} call and always removed when it exits.
 */
public final class RequestContext {

    private final Object request;
    private final String method;
    private final String uri;
    private final boolean sampled;
    private final boolean profiled;
    /** Spring transaction boundaries currently open on this thread (@Transactional, TransactionTemplate). */
    public int transactionDepth;
    /** OpenEntityManagerInViewInterceptor ran for this request. */
    public boolean openInView;
    private final long startNanos;
    private final RequestStats stats = new RequestStats();
    int depth;

    /** Sanitized endpoint once the Spring pattern is known (it never changes afterwards). */
    private volatile String resolvedEndpoint;

    RequestContext(Object request, String method, String uri, boolean sampled, boolean profiled) {
        this.request = request;
        this.method = method;
        this.uri = uri;
        this.sampled = sampled;
        this.profiled = sampled && profiled;
        this.startNanos = System.nanoTime();
    }

    public Object request() { return request; }
    public String method() { return method; }
    public String uri() { return uri; }
    public boolean sampled() { return sampled; }
    /** JPA profile recorded for this request (FQ_PROFILE_SAMPLE among sampled requests). */
    public boolean profiled() { return profiled; }
    public long startNanos() { return startNanos; }
    public int depth() { return depth; }
    public RequestStats stats() { return stats; }

    public String resolvedEndpoint() { return resolvedEndpoint; }
    public void resolvedEndpoint(String endpoint) { this.resolvedEndpoint = endpoint; }

    /** Sanitized {@code METHOD /raw/uri}, used until (or if never) a Spring pattern is known. */
    private volatile String fallbackEndpoint;

    public String fallbackEndpoint() { return fallbackEndpoint; }
    public void fallbackEndpoint(String endpoint) { this.fallbackEndpoint = endpoint; }
}
