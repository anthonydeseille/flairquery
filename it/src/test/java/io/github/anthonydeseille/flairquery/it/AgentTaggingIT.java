package io.github.anthonydeseille.flairquery.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent enabled: tags reach PostgreSQL, N+1 detected on the trap, never on the witness. */
class AgentTaggingIT {

    private static final String DEMO = "io.github.anthonydeseille.flairquery.demo";
    private static DemoAppProcess app;

    @BeforeAll
    static void start() throws Exception {
        app = DemoAppProcess.start("agent", true, DemoAppProcess.env());
        ItEnvironment.ensureSeeded();
        ItEnvironment.resetPgStatStatements();
        Traffic.run(app, 2);
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) app.close();
    }

    @Test
    void fqCommentAppearsInPgStatStatements() {
        List<String> tagged = ItEnvironment.taggedStatements();

        assertThat(tagged).isNotEmpty();
        assertThat(tagged).allSatisfy(q -> assertThat(q).startsWith("/* fq:app=it-agent,endpoint="));
        assertThat(tagged).anySatisfy(q -> assertThat(q)
                .startsWith("/* fq:app=it-agent,endpoint=GET /authors,caller=" + DEMO + ".web.AuthorController#toDto:"));
        assertThat(tagged).anySatisfy(q -> assertThat(q)
                .startsWith("/* fq:app=it-agent,endpoint=GET /publishers/{id},caller=" + DEMO + ".web.PublisherController#get:"));
    }

    @Test
    void nPlusOneTrapIsDetectedAndWitnessIsNot() throws Exception {
        JsonNode n1 = app.agentJson("/fq/nplusone").get("nplusone");
        List<String> endpoints = StreamSupport.stream(n1.spliterator(), false)
                .map(n -> n.get("endpoint").asText()).toList();

        assertThat(endpoints).contains("GET /authors");
        assertThat(endpoints).doesNotContain("GET /publishers/{id}");

        JsonNode authors = StreamSupport.stream(n1.spliterator(), false)
                .filter(n -> n.get("endpoint").asText().equals("GET /authors")).findFirst().orElseThrow();
        assertThat(authors.get("sql").asText()).contains("from book");
        assertThat(authors.get("maxQueriesPerRequest").asLong()).isEqualTo(20); // one per author
        assertThat(authors.get("caller").asText()).startsWith(DEMO + ".web.AuthorController#toDto:");
    }

    @Test
    void witnessRunsExactlyOneQueryPerRequestThroughHikari() throws Exception {
        JsonNode witness = StreamSupport.stream(app.agentJson("/fq/endpoints").get("endpoints").spliterator(), false)
                .filter(e -> e.get("endpoint").asText().equals("GET /publishers/{id}")).findFirst().orElseThrow();

        assertThat(witness.get("queriesPerRequest").asDouble()).isEqualTo(1.0); // no double count proxy+driver
        assertThat(witness.get("nPlusOneRequests").asLong()).isZero();
    }

    @Test
    void topAndMetricsAreExposed() throws Exception {
        JsonNode top = app.agentJson("/fq/top?limit=5");
        assertThat(top.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(top.get("top")).isNotEmpty();
        assertThat(top.get("top").get(0).has("caller")).isTrue();

        String metrics = app.agent("/metrics").body();
        assertThat(metrics)
                // per call: authors page + Spring Data count + 20 x books (N+1) = 22; 2 rounds
                .contains("fq_queries_count{app=\"it-agent\",endpoint=\"GET /authors\"} 44")
                .contains("fq_nplusone{app=\"it-agent\",endpoint=\"GET /authors\"} 2")
                .contains("fq_agent_active{app=\"it-agent\"} 1");
    }

    @Test
    void nPlusOneIsLoggedAsStructuredJson() throws Exception {
        String line = app.logText().lines()
                .filter(l -> l.contains("\"msg\":\"N+1 detected\"") && l.contains("GET /authors"))
                .findFirst().orElseThrow();

        JsonNode log = ItEnvironment.JSON.readTree(line);
        assertThat(log.get("level").asText()).isEqualTo("WARN");
        assertThat(log.get("logger").asText()).isEqualTo("flairquery-agent");
        assertThat(log.get("queriesInRequest").asInt()).isEqualTo(20);
    }
}
