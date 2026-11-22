package io.github.anthonydeseille.flairquery.it;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FQ_ENABLED=false: the agent jar is loaded but installs nothing. */
class AgentDisabledIT {

    @Test
    void noInstrumentationAtAll() throws Exception {
        try (DemoAppProcess app = DemoAppProcess.start("disabled", true, DemoAppProcess.env("FQ_ENABLED", "false"))) {
            ItEnvironment.ensureSeeded();
            ItEnvironment.resetPgStatStatements();

            Traffic.run(app, 1);

            assertThat(ItEnvironment.taggedStatements()).isEmpty();
            assertThatThrownBy(() -> app.agent("/metrics")).isInstanceOf(ConnectException.class);
            assertThat(app.logText()).contains("agent disabled (FQ_ENABLED=false)")
                    .doesNotContain("\"msg\":\"agent started\"");
        }
    }
}
