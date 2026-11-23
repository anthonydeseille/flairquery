package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import io.github.anthonydeseille.flairquery.agent.core.FqRuntime;
import io.github.anthonydeseille.flairquery.agent.http.FqHttpServer;
import io.github.anthonydeseille.flairquery.agent.jdbc.PrepareStatementAdvice;
import io.github.anthonydeseille.flairquery.agent.jdbc.PreparedExecuteAdvice;
import io.github.anthonydeseille.flairquery.agent.jdbc.SqlArgumentAdvice;
import io.github.anthonydeseille.flairquery.agent.jdbc.StatementExecuteAdvice;
import io.github.anthonydeseille.flairquery.agent.log.JsonLog;
import io.github.anthonydeseille.flairquery.agent.servlet.HttpServletServiceAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Agent entry point ({@code -javaagent:/fq/agent.jar}).
 *
 * <p>Instrumentation points:
 * <ul>
 *   <li>{@code jakarta.servlet.http.HttpServlet#service(HttpServletRequest, HttpServletResponse)}
 *       and overrides: opens / closes the request context;</li>
 *   <li>{@code java.sql.Connection#prepareStatement(String, ...)} implementations
 *       (driver and pool proxies, e.g. HikariCP): tags the SQL;</li>
 *   <li>{@code java.sql.Statement#execute / executeQuery / executeUpdate / executeLargeUpdate(String, ...)}
 *       implementations: tags and measures; {@code addBatch(String)}: tags;</li>
 *   <li>no-argument {@code execute*} / {@code executeBatch} (prepared statements): measures.</li>
 * </ul>
 * {@code prepareCall} is deliberately not tagged: JDBC escape syntax ({@code {call ...}}) must stay first.
 *
 * <p>Fail-safe: any failure during startup is logged and the application starts without instrumentation.
 */
public final class FqAgent {

    private static final String SELF = "io.github.anthonydeseille.flairquery.";

    private FqAgent() { }

    public static void premain(String args, Instrumentation inst) {
        start(System.getenv(), inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(System.getenv(), inst);
    }

    /** Never throws. @return the installed handle, or {@code null} when disabled or failed. */
    public static Installation start(Map<String, String> env, Instrumentation inst) {
        try {
            FqConfig config = FqConfig.fromEnv(env);
            JsonLog.setThreshold(config.logLevel());
            if (!config.enabled()) {
                JsonLog.info("agent disabled (FQ_ENABLED=false), no instrumentation installed");
                return null;
            }
            return install(config, inst);
        } catch (Throwable t) {
            try {
                Fq.reset();
                JsonLog.error("agent initialisation failed, application continues without instrumentation", t);
            } catch (Throwable ignored) {
                // nothing else we can do
            }
            return null;
        }
    }

    static Installation install(FqConfig config, Instrumentation inst) {
        if (config.fault("init")) {
            throw new IllegalStateException("FQ_TEST_FAULT=init");
        }
        FqRuntime runtime = new FqRuntime(config);
        ResettableClassFileTransformer transformer = null;
        try {
            transformer = agentBuilder().installOn(inst);
            startTypeCacheCleaner();
            Fq.install(runtime);
        } catch (Throwable t) {
            Fq.reset();
            if (transformer != null) {
                try {
                    transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                } catch (Throwable ignored) {
                    // advices stay but call the no-op hooks
                }
            }
            throw t;
        }
        FqHttpServer http = new FqHttpServer(runtime.registry());
        http.start(config.httpPort()); // failure only disables the endpoints
        JsonLog.info("agent started",
                "app", config.appName(),
                "basePackages", config.basePackages(),
                "nPlusOneThreshold", config.nPlusOneThreshold(),
                "samplingRate", config.samplingRate(),
                "httpPort", http.port());
        return new Installation(runtime, transformer, inst, http);
    }

    /**
     * Type descriptions are resolved from class files to evaluate {@code hasSuperType}; without a cache the
     * same super types (Object, Spring/Hibernate bases...) are parsed thousands of times at startup.
     * Measured on kind with a 500m CPU limit: startup 46.7 s without agent, 69.9 s with the uncached agent.
     * The cache is cleared every minute so it never grows with the application's lifetime.
     */
    private static final ConcurrentHashMap<ClassLoader, TypePool.CacheProvider> TYPE_CACHE = new ConcurrentHashMap<>();

    private static void startTypeCacheCleaner() {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flairquery-typecache-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(TYPE_CACHE::clear, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Packages that can contain neither a servlet to intercept nor a JDBC implementation: skipped before
     * any type matching. Spring is skipped except its web (DispatcherServlet) and jdbc packages; pools that
     * implement java.sql.Connection (HikariCP, commons-dbcp2, c3p0...) and drivers are never skipped.
     */
    static ElementMatcher.Junction<TypeDescription> ignored() {
        return nameStartsWith("java.")
                .or(nameStartsWith("javax."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."))
                .or(nameStartsWith("net.bytebuddy."))
                .or(nameStartsWith(SELF + "agent."))
                .or(nameStartsWith(SELF + "shaded."))
                .or(nameStartsWith(SELF + "sql."))
                .or(nameStartsWith("org.springframework.")
                        .and(not(nameStartsWith("org.springframework.web.")))
                        .and(not(nameStartsWith("org.springframework.jdbc."))))
                .or(nameStartsWith("com.fasterxml."))
                .or(nameStartsWith("ch.qos.logback."))
                .or(nameStartsWith("org.slf4j."))
                .or(nameStartsWith("org.apache.logging."))
                .or(nameStartsWith("org.yaml.snakeyaml."))
                .or(nameStartsWith("io.micrometer."))
                .or(nameStartsWith("org.aspectj."))
                .or(nameStartsWith("kotlin."))
                .or(nameStartsWith("scala."))
                .or(nameStartsWith("org.jboss.logging."))
                .or(nameStartsWith("org.hibernate.validator."))
                .or(nameStartsWith("org.antlr."))
                .or(nameStartsWith("org.thymeleaf."))
                // ORM / migration tools: no java.sql.Connection or Statement implementation inside
                .or(nameStartsWith("org.hibernate."))
                .or(nameStartsWith("liquibase."))
                .or(nameStartsWith("org.liquibase."))
                // Tomcat internals: requests reach jakarta.servlet.http.HttpServlet#service anyway
                .or(nameStartsWith("org.apache.catalina."))
                .or(nameStartsWith("org.apache.coyote."))
                .or(nameStartsWith("org.apache.tomcat.util."))
                .or(isSynthetic());
    }

    static AgentBuilder agentBuilder() {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(new AgentBuilder.PoolStrategy.WithTypePoolCache.Simple(TYPE_CACHE))
                .with(new LoggingListener())
                .ignore(ignored())
                // HTTP request context
                .type(not(isInterface()).and(hasSuperType(named("jakarta.servlet.http.HttpServlet"))))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .advice(named("service")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("jakarta.servlet.http.HttpServletRequest")))
                                        .and(takesArgument(1, named("jakarta.servlet.http.HttpServletResponse")))
                                        .and(not(isAbstract())),
                                HttpServletServiceAdvice.class.getName()))
                // JDBC: prepareStatement(String, ...) -> tag, remember statement SQL
                .type(not(isInterface()).and(hasSuperType(named("java.sql.Connection"))))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .advice(named("prepareStatement")
                                        .and(takesArgument(0, String.class))
                                        .and(not(isAbstract())),
                                PrepareStatementAdvice.class.getName()))
                // JDBC: Statement#execute*(String, ...) -> tag + measure; addBatch(String) -> tag
                .type(not(isInterface()).and(hasSuperType(named("java.sql.Statement"))))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .advice(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                                        .and(takesArgument(0, String.class))
                                        .and(not(isAbstract())),
                                StatementExecuteAdvice.class.getName())
                        .advice(named("addBatch")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, String.class))
                                        .and(not(isAbstract())),
                                SqlArgumentAdvice.class.getName())
                        .advice(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                                        "executeBatch", "executeLargeBatch")
                                        .and(takesArguments(0))
                                        .and(not(isAbstract())),
                                PreparedExecuteAdvice.class.getName()));
    }

    /** Handle on an installed agent (used by tests and, later, by the HTTP server lifecycle). */
    public record Installation(FqRuntime runtime, ResettableClassFileTransformer transformer, Instrumentation inst,
                               FqHttpServer http) {

        public void uninstall() {
            Fq.reset();
            http.stop();
            transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        }
    }

    private static final class LoggingListener extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                                     boolean loaded, DynamicType dynamicType) {
            JsonLog.debug("instrumented", "type", type.getName());
        }

        @Override
        public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable t) {
            JsonLog.debug("instrumentation skipped", "type", typeName, "error", String.valueOf(t));
        }
    }
}
