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
    /** Lazy loads in progress (collection role / entity name), innermost last; created on first use. */
    private java.util.ArrayDeque<String> lazyLoading;
    /** Result sets of this request's measured executions -> their statement entry; created on first use. */
    private java.util.IdentityHashMap<Object, RequestStats.Entry> resultSets;

    public java.util.ArrayDeque<String> lazyLoading() {
        if (lazyLoading == null) lazyLoading = new java.util.ArrayDeque<>(4);
        return lazyLoading;
    }

    /** Innermost lazy load in progress, or null. */
    public String currentLazyLoad() {
        return lazyLoading == null ? null : lazyLoading.peekLast();
    }

    public java.util.IdentityHashMap<Object, RequestStats.Entry> resultSets() {
        if (resultSets == null) resultSets = new java.util.IdentityHashMap<>();
        return resultSets;
    }

    /** Statement entry of a registered result set, or null (unknown result sets included). */
    public RequestStats.Entry resultSetEntry(Object resultSet) {
        return resultSets == null ? null : resultSets.get(resultSet);
    }

    public boolean knowsResultSet(Object resultSet) {
        return resultSets != null && resultSets.containsKey(resultSet);
    }
    /** Fast path: the result set read last (Hibernate reads one result set at a time). */
    public Object lastResultSet;
    public RequestStats.Entry lastResultSetEntry;
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
