package io.github.anthonydeseille.flairquery.fixture;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * Test-only probe: records the SQL argument as seen INSIDE the H2 driver methods (at method exit, i.e.
 * after any FlairQuery advice has rewritten the parameter). H2's own system views truncate or strip
 * comments, so this is the reliable way to see exactly what the driver received.
 */
public final class DriverSqlRecorder {

    public static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    private DriverSqlRecorder() { }

    public static ResettableClassFileTransformer install(Instrumentation inst) {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(namedOneOf("org.h2.jdbc.JdbcConnection", "org.h2.jdbc.JdbcStatement"))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .advice(namedOneOf("prepareStatement", "execute", "executeQuery", "executeUpdate")
                                .and(takesArgument(0, String.class)), RecordAdvice.class.getName()))
                .installOn(inst);
    }

    public static void record(String sql) {
        RECORDED.add(sql);
    }

    public static String lastContaining(String marker) {
        for (int i = RECORDED.size() - 1; i >= 0; i--) {
            if (RECORDED.get(i).contains(marker)) {
                return RECORDED.get(i);
            }
        }
        throw new IllegalStateException("no SQL recorded containing " + marker);
    }

    public static final class RecordAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) String sql) {
            DriverSqlRecorder.record(sql);
        }
    }
}
