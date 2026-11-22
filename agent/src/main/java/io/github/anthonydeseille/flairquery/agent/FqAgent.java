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

import java.lang.instrument.Instrumentation;
import java.util.Map;

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

    static AgentBuilder agentBuilder() {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(new LoggingListener())
                .ignore(nameStartsWith("java.")
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith(SELF + "agent."))
                        .or(nameStartsWith(SELF + "shaded."))
                        .or(isSynthetic()))
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
