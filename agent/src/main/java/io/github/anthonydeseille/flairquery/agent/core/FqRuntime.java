package io.github.anthonydeseille.flairquery.agent.core;

import io.github.anthonydeseille.flairquery.agent.FqConfig;
import io.github.anthonydeseille.flairquery.agent.bootstrap.FqHooks;
import io.github.anthonydeseille.flairquery.agent.caller.CallerResolver;
import io.github.anthonydeseille.flairquery.agent.context.RequestContext;
import io.github.anthonydeseille.flairquery.agent.context.RequestContextHolder;
import io.github.anthonydeseille.flairquery.agent.jdbc.StatementSqlRegistry;
import io.github.anthonydeseille.flairquery.agent.log.JsonLog;
import io.github.anthonydeseille.flairquery.agent.metrics.Registry;
import io.github.anthonydeseille.flairquery.agent.metrics.RequestStats;
import io.github.anthonydeseille.flairquery.agent.profile.ProfileRegistry;
import io.github.anthonydeseille.flairquery.sql.SqlNormalizer;
import io.github.anthonydeseille.flairquery.agent.servlet.ServletRequestAccessor;
import io.github.anthonydeseille.flairquery.agent.spi.FqListener;
import io.github.anthonydeseille.flairquery.agent.spi.RequestSummary;
import io.github.anthonydeseille.flairquery.sql.SqlTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/** The live implementation behind {@link io.github.anthonydeseille.flairquery.agent.bootstrap.Fq}. */
public final class FqRuntime implements FqHooks {

    /** Endpoint value for statements executed outside an HTTP request (startup, schedulers, ...). */
    public static final String NO_ENDPOINT = "none";
    static final String UNKNOWN_SQL = "<unknown statement>";

    /** Execution nesting per thread: only the outermost execute (pool proxy) is measured. */
    private static final ThreadLocal<int[]> QUERY_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final FqConfig config;
    private final CallerResolver callerResolver;
    private final StatementSqlRegistry statements = new StatementSqlRegistry();
    private final SqlNormalizer normalizer = new SqlNormalizer();
    private final Registry registry;
    private final List<FqListener> listeners = new CopyOnWriteArrayList<>();
    private final ProfileRegistry profiles;

    public FqRuntime(FqConfig config) {
        this.config = config;
        this.callerResolver = new CallerResolver(config.basePackages());
        this.registry = new Registry(config.appName(), config.nPlusOneThreshold());
        this.listeners.add(registry);
        this.profiles = new ProfileRegistry(config.nPlusOneThreshold());
        if (config.profile()) {
            this.listeners.add(profiles);
        }
    }

    public FqConfig config() { return config; }
    public Registry registry() { return registry; }
    public ProfileRegistry profiles() { return profiles; }

    public void addListener(FqListener listener) {
        listeners.add(listener);
    }

    // ---------------------------------------------------------------- tagging

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

    // ---------------------------------------------------------------- measurement

    @Override
    public void statementPrepared(Object statement, String sql) {
        statements.put(statement, sql);
    }

    @Override
    public long queryStart() {
        int[] depth = QUERY_DEPTH.get();
        return depth[0]++ == 0 ? System.nanoTime() : -1L;
    }

    @Override
    public void queryEnd(Object statement, String sql, long start, Throwable thrown) {
        queryEnd(statement, sql, start, thrown, null);
    }

    @Override
    public void queryEnd(Object statement, String sql, long start, Throwable thrown, Object result) {
        int[] depth = QUERY_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
        if (start < 0) {
            return;
        }
        long nanos = System.nanoTime() - start;
        if (config.fault("metrics")) {
            throw new IllegalStateException("FQ_TEST_FAULT=metrics");
        }
        RequestContext ctx = RequestContextHolder.current();
        if (ctx != null && !ctx.sampled()) {
            return;
        }
        String text = sql != null ? sql : statements.get(statement);
        SqlNormalizer.Parsed parsed = text == null
                ? new SqlNormalizer.Parsed(UNKNOWN_SQL, SqlTag.UNKNOWN)
                : normalizer.parse(text);
        if (ctx != null) {
            String lazyRole = ctx.profiled() ? ctx.lazyLoading.peekLast() : null;
            RequestStats.Entry entry = ctx.stats().record(parsed.normalizedSql(), parsed.caller(), nanos,
                    thrown != null, lazyRole);
            if (ctx.profiled() && result instanceof java.sql.ResultSet) {
                if (ctx.resultSets.size() < MAX_RESULT_SETS) {
                    ctx.resultSets.put(result, entry);
                }
                ctx.lastResultSet = result;
                ctx.lastResultSetEntry = entry;
            }
        } else {
            for (FqListener l : listeners) {
                l.onBackgroundQuery(config.appName(), parsed.normalizedSql(), parsed.caller(), nanos, thrown != null);
            }
        }
    }

    // ---------------------------------------------------------------- HTTP requests

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
        double profileRate = config.profileSample();
        boolean profiled = config.profile()
                && (profileRate >= 1.0 || (profileRate > 0.0 && ThreadLocalRandom.current().nextDouble() < profileRate));
        RequestContextHolder.enter(request, method, uri, sampled, profiled);
    }

    @Override
    public void requestExit(Object request, Object response, Throwable thrown) {
        RequestContext ctx = RequestContextHolder.exit();
        if (ctx == null || !ctx.sampled()) {
            return;
        }
        long duration = System.nanoTime() - ctx.startNanos();
        String endpoint = endpoint(ctx);
        RequestStats stats = ctx.stats();
        List<String> nPlusOne = new ArrayList<>(1);
        for (Map.Entry<String, RequestStats.Entry> e : stats.bySql().entrySet()) {
            if (e.getValue().count() > config.nPlusOneThreshold()) {
                nPlusOne.add(e.getKey());
            }
        }
        int status = thrown != null ? 500 : ServletRequestAccessor.status(response);
        RequestSummary summary = new RequestSummary(config.appName(), endpoint, status, duration, stats,
                List.copyOf(nPlusOne), ctx.profiled());
        for (FqListener l : listeners) {
            l.onRequest(summary);
        }
        for (String sql : nPlusOne) {
            if (registry.isNewNPlusOne(endpoint, sql)) {
                RequestStats.Entry e = stats.bySql().get(sql);
                JsonLog.warn("N+1 detected", null,
                        "endpoint", endpoint, "sql", sql, "caller", e.caller(),
                        "queriesInRequest", e.count(), "threshold", config.nPlusOneThreshold());
            }
        }
        if (JsonLog.isEnabled(JsonLog.Level.DEBUG)) {
            JsonLog.debug("request", "endpoint", endpoint, "status", status,
                    "durationMs", duration / 1e6, "queries", stats.queries(), "sqlMs", stats.sqlNanos() / 1e6);
        }
    }

    // ---------------------------------------------------------------- JPA profile (phase 3)

    private static RequestContext profiled() {
        RequestContext ctx = RequestContextHolder.current();
        return ctx != null && ctx.profiled() ? ctx : null;
    }

    @Override
    public void entityLoaded(Object entity) {
        RequestContext ctx = profiled();
        if (ctx != null) {
            ctx.stats().entityLoaded(entityName(entity.getClass()));
        }
    }

    /** Entity class name without bytecode-generated proxy suffixes (Hibernate$HibernateProxy$...). */
    static String entityName(Class<?> type) {
        String name = type.getName();
        int cut = name.indexOf("$HibernateProxy");
        return cut > 0 ? name.substring(0, cut) : name;
    }

    /** Result sets remembered per request (bounded: a request reading more is not profiled further). */
    static final int MAX_RESULT_SETS = 10_000;

    @Override
    public void rowRead(Object resultSet) {
        RequestContext ctx = profiled();
        if (ctx == null) {
            return;
        }
        if (resultSet == ctx.lastResultSet) {
            ctx.stats().rowRead(ctx.lastResultSetEntry);
            return;
        }
        if (!ctx.resultSets.containsKey(resultSet)) {
            return;     // driver result set behind a pool proxy, or not produced by a measured execution
        }
        RequestStats.Entry entry = ctx.resultSets.get(resultSet);
        ctx.lastResultSet = resultSet;
        ctx.lastResultSetEntry = entry;
        ctx.stats().rowRead(entry);
    }

    @Override
    public long lazyLoadStart(String kind, String role) {
        RequestContext ctx = profiled();
        if (ctx == null) {
            return -1L;
        }
        ctx.lazyLoading.addLast(role == null ? SqlTag.UNKNOWN : role);
        return System.nanoTime();
    }

    @Override
    public void lazyLoadEnd(String kind, String role, long start, Throwable thrown) {
        RequestContext ctx = profiled();
        if (ctx == null) {
            return;
        }
        ctx.lazyLoading.pollLast();
        String caller = callerResolver.resolve();
        ctx.stats().lazyLoaded(kind, role == null ? SqlTag.UNKNOWN : role, System.nanoTime() - start,
                ctx.transactionDepth == 0, caller);
    }

    @Override
    public void attributeRead(Object entity, String getter) {
        RequestContext ctx = profiled();
        if (ctx == null) {
            return;
        }
        io.github.anthonydeseille.flairquery.agent.model.EntityModel model =
                io.github.anthonydeseille.flairquery.agent.model.EntityModels.of(entity.getClass());
        if (model == null || model.propertyAccess()) {
            return;
        }
        int index = model.attributeOfGetter(getter);
        if (index >= 0) {
            ctx.stats().attributeRead(model.className(), index);
        }
    }

    @Override
    public void inMemoryPagination() {
        RequestContext ctx = profiled();
        if (ctx != null) {
            ctx.stats().inMemoryPagination();
        }
    }

    @Override
    public void transactionEnter() {
        RequestContext ctx = RequestContextHolder.current();
        if (ctx != null) {
            ctx.transactionDepth++;
        }
    }

    @Override
    public void transactionExit() {
        RequestContext ctx = RequestContextHolder.current();
        if (ctx != null && ctx.transactionDepth > 0) {
            ctx.transactionDepth--;
        }
    }

    @Override
    public void openInView() {
        RequestContext ctx = profiled();
        if (ctx != null) {
            ctx.stats().openInView(true);
        }
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
