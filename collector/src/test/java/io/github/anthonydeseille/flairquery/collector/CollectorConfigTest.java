package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.config.CollectorConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorConfigTest {

    @Test
    void defaults() {
        CollectorConfig c = CollectorConfig.fromEnv(Map.of(
                "FQ_COLLECTOR_TARGET_URL", "jdbc:postgresql://postgres:5432/flairquery",
                "FQ_COLLECTOR_STORE_URL", "jdbc:postgresql://fq-store:5432/fqstore"));

        assertThat(c.targets()).hasSize(1);
        assertThat(c.targets().get(0).name()).isEqualTo("postgres:5432");
        assertThat(c.targets().get(0).user()).isEqualTo("fq_reader");
        assertThat(c.interval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(c.retention()).isEqualTo(Duration.ofHours(72));
        assertThat(c.httpPort()).isEqualTo(9465);
        assertThat(c.targets().get(0).toString()).doesNotContain("fq_reader@").doesNotContain("password");
    }

    @Test
    void missingTargetIsAClearError() {
        assertThatThrownBy(() -> CollectorConfig.fromEnv(Map.of("FQ_COLLECTOR_STORE_URL", "jdbc:x")))
                .hasMessageContaining("FQ_COLLECTOR_TARGET_URL");
    }
}
