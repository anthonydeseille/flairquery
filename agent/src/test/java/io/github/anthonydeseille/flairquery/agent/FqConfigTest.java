package io.github.anthonydeseille.flairquery.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FqConfigTest {

    @Test
    void defaults() {
        FqConfig c = FqConfig.defaults();

        assertThat(c.enabled()).isTrue();
        assertThat(c.appName()).isEqualTo(FqConfig.DEFAULT_APP_NAME);
        assertThat(c.basePackages()).isEmpty();
        assertThat(c.nPlusOneThreshold()).isEqualTo(5);
        assertThat(c.samplingRate()).isEqualTo(1.0);
        assertThat(c.httpPort()).isEqualTo(9464);
        assertThat(c.testFaults()).isEmpty();
        assertThat(c.profile()).isTrue();
        assertThat(c.profileEntities()).isTrue();
        assertThat(c.profileSample()).isEqualTo(1.0);
    }

    @Test
    void readsEnvironment() {
        FqConfig c = FqConfig.fromEnv(Map.of(
                "FQ_ENABLED", "false",
                "FQ_APP_NAME", "demo-app",
                "FQ_BASE_PACKAGES", "com.acme.app, com.acme.lib. ;com.acme.app",
                "FQ_NPLUSONE_THRESHOLD", "10",
                "FQ_SAMPLING_RATE", "0.25",
                "FQ_HTTP_PORT", "19464"));

        assertThat(c.enabled()).isFalse();
        assertThat(c.appName()).isEqualTo("demo-app");
        assertThat(c.basePackages()).isEqualTo(List.of("com.acme.app", "com.acme.lib"));
        assertThat(c.nPlusOneThreshold()).isEqualTo(10);
        assertThat(c.samplingRate()).isEqualTo(0.25);
        assertThat(c.httpPort()).isEqualTo(19464);
    }

    @Test
    void invalidValuesFallBackToDefaultsInsteadOfFailing() {
        FqConfig c = FqConfig.fromEnv(Map.of(
                "FQ_ENABLED", "maybe",
                "FQ_APP_NAME", "bad*/name",
                "FQ_NPLUSONE_THRESHOLD", "-3",
                "FQ_SAMPLING_RATE", "2.5",
                "FQ_HTTP_PORT", "http",
                "FQ_LOG_LEVEL", "verbose"));

        assertThat(c.enabled()).isTrue();
        assertThat(c.appName()).isEqualTo("bad_/name"); // "*" removed: no comment can be opened or closed
        assertThat(c.nPlusOneThreshold()).isEqualTo(5);
        assertThat(c.samplingRate()).isEqualTo(1.0);
        assertThat(c.httpPort()).isEqualTo(9464);
    }

    @Test
    void profileSwitches() {
        FqConfig off = FqConfig.fromEnv(Map.of("FQ_PROFILE", "false", "FQ_PROFILE_ENTITIES", "true"));
        assertThat(off.profile()).isFalse();
        assertThat(off.profileEntities()).as("entities need the profile").isFalse();

        FqConfig sampled = FqConfig.fromEnv(Map.of("FQ_PROFILE_ENTITIES", "off", "FQ_PROFILE_SAMPLE", "0.1"));
        assertThat(sampled.profile()).isTrue();
        assertThat(sampled.profileEntities()).isFalse();
        assertThat(sampled.profileSample()).isEqualTo(0.1);
        assertThat(FqConfig.fromEnv(Map.of("FQ_PROFILE_SAMPLE", "2")).profileSample()).isEqualTo(1.0);
    }
}
