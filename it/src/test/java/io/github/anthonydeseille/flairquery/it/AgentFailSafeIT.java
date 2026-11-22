package io.github.anthonydeseille.flairquery.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-safe: internal agent failures (injected with the test-only FQ_TEST_FAULT) never change what the
 * application returns, and never prevent its JVM from exiting.
 */
class AgentFailSafeIT {

    private static Map<String, String> reference;

    @BeforeAll
    static void referenceWithoutAgent() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("reference", false, DemoAppProcess.env())) {
            ItEnvironment.ensureSeeded();
            reference = Traffic.bodies(app);
        }
    }

    @Test
    void agentRuntimeExceptionsDoNotAffectBusinessResponses() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("faulty", true,
                DemoAppProcess.env("FQ_TEST_FAULT", "tagger,request,metrics,caller"))) {
            ItEnvironment.resetPgStatStatements();

            assertThat(Traffic.bodies(app)).isEqualTo(reference);
            Traffic.run(app, 3);

            assertThat(ItEnvironment.taggedStatements()).isEmpty();
            String metrics = app.agent("/metrics").body();
            assertThat(metrics).containsPattern("fq_agent_errors\\{app=\"it-faulty\"} [1-9][0-9]*");
            // > 100 internal errors: the circuit breaker switched the agent off
            assertThat(metrics).contains("fq_agent_active{app=\"it-faulty\"} 0");
        }
    }

    @Test
    void agentInitFailureLetsTheApplicationStartUninstrumented() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("init-failure", true,
                DemoAppProcess.env("FQ_TEST_FAULT", "init"))) {
            ItEnvironment.resetPgStatStatements();

            assertThat(Traffic.bodies(app)).isEqualTo(reference);
            assertThat(ItEnvironment.taggedStatements()).isEmpty();
            assertThat(app.logText()).contains("agent initialisation failed, application continues without instrumentation");
        }
    }

    @Test
    void responsesWithAgentAreIdenticalToResponsesWithout() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("compare", true, DemoAppProcess.env())) {
            assertThat(Traffic.bodies(app)).isEqualTo(reference);
        }
    }

    @Test
    void jvmExitsWhenTheApplicationFailsToStart() throws Exception {
        // nothing listens on port 1: Spring startup fails; the agent must not keep the JVM alive
        try (DemoAppProcess app = DemoAppProcess.launch("startup-failure", true,
                DemoAppProcess.env("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "2000"),
                "jdbc:postgresql://localhost:1/none")) {
            assertThat(app.waitForExit(Duration.ofSeconds(90)))
                    .as("JVM still alive after a failed startup:\n" + app.logTail())
                    .isTrue();
            assertThat(app.logText()).contains("\"msg\":\"agent started\"");
        }
    }
}
