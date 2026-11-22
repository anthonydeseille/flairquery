package io.github.anthonydeseille.flairquery.agent;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import io.github.anthonydeseille.flairquery.agent.context.RequestContextHolder;
import io.github.anthonydeseille.flairquery.agent.tag.SqlTag;
import io.github.anthonydeseille.flairquery.fixture.DemoServlet;
import io.github.anthonydeseille.flairquery.fixture.DriverSqlRecorder;
import io.github.anthonydeseille.flairquery.fixture.FakeHttp;
import io.github.anthonydeseille.flairquery.fixture.SqlProbe;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real bytecode instrumentation (self-attached agent) against H2, directly and through HikariCP. */
class InstrumentationTest {

    private static final String FIXTURE = "io.github.anthonydeseille.flairquery.fixture";

    private static FqAgent.Installation installation;
    private static JdbcDataSource h2;
    private static HikariDataSource hikari;

    private static ResettableClassFileTransformer recorder;

    @BeforeAll
    static void install() {
        Instrumentation inst = ByteBuddyAgent.install();
        recorder = DriverSqlRecorder.install(inst);
        installation = FqAgent.start(Map.of(
                "FQ_APP_NAME", "test-app",
                "FQ_BASE_PACKAGES", FIXTURE), inst);
        assertThat(installation).isNotNull();

        h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:fq;DB_CLOSE_DELAY=-1");
        HikariConfig hc = new HikariConfig();
        hc.setDataSource(h2);
        hc.setMaximumPoolSize(2);
        hikari = new HikariDataSource(hc);
    }

    @AfterAll
    static void uninstall() {
        hikari.close();
        installation.uninstall();
        recorder.reset(installation.inst(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    @AfterEach
    void cleanThread() {
        RequestContextHolder.clear();
    }

    @Test
    void preparedStatementIsTaggedOutsideRequests() throws Exception {
        SqlProbe probe = new SqlProbe(h2);
        String seen = probe.preparedSelfText();

        assertThat(seen).startsWith("/* fq:app=test-app,endpoint=none,caller=" + FIXTURE + ".SqlProbe#preparedSelfText:");
        assertThat(SqlTag.strip(seen)).isEqualTo(probe.lastProbeSql());
    }

    @Test
    void plainStatementIsTagged() throws Exception {
        SqlProbe probe = new SqlProbe(h2);
        String seen = probe.statementSelfText();

        assertThat(SqlTag.parse(seen).orElseThrow().caller()).startsWith(FIXTURE + ".SqlProbe#statementSelfText:");
        assertThat(SqlTag.strip(seen)).isEqualTo(probe.lastProbeSql());
    }

    @Test
    void hikariProxyAndDriverAreBothInstrumentedButTagOnlyOnce() throws Exception {
        SqlProbe p1 = new SqlProbe(hikari);
        SqlProbe p2 = new SqlProbe(hikari);
        String prepared = p1.preparedSelfText();
        String statement = p2.statementSelfText();

        assertThat(SqlTag.strip(prepared)).isEqualTo(p1.lastProbeSql());
        assertThat(SqlTag.strip(statement)).isEqualTo(p2.lastProbeSql());
        for (String seen : new String[]{prepared, statement}) {
            assertThat(seen.split("/\\* fq:", -1)).hasSize(2); // exactly one tag
            assertThat(SqlTag.parse(seen).orElseThrow().caller()).startsWith(FIXTURE + ".SqlProbe#");
        }
    }

    @Test
    void servletRequestProvidesTheEndpointPattern() throws Exception {
        DemoServlet servlet = new DemoServlet();
        SqlProbe probe = new SqlProbe(hikari);
        servlet.beforeMapping = probe::preparedSelfText;  // e.g. a filter querying before handler mapping
        servlet.pattern = "/authors/{id}";
        servlet.afterMapping = probe::preparedSelfText;

        servlet.service(FakeHttp.request("GET", "/authors/42"), FakeHttp.response());

        assertThat(SqlTag.parse(servlet.seenSql.get(0)).orElseThrow().endpoint()).isEqualTo("GET /authors/42");
        assertThat(SqlTag.parse(servlet.seenSql.get(1)).orElseThrow().endpoint()).isEqualTo("GET /authors/{id}");
        assertThat(RequestContextHolder.current()).as("context removed after the request").isNull();
    }

    @Test
    void contextIsCleanedWhenTheServletThrows() {
        DemoServlet servlet = new DemoServlet();
        servlet.pattern = "/boom";
        servlet.failure = new IllegalStateException("business failure");

        assertThatThrownBy(() -> servlet.service(FakeHttp.request("POST", "/boom"), FakeHttp.response()))
                .hasMessage("business failure");
        assertThat(RequestContextHolder.current()).isNull();
    }

    @Test
    void nestedServiceCallsShareOneContext() throws Exception {
        DemoServlet inner = new DemoServlet();
        inner.pattern = "/inner/{x}";
        SqlProbe probe = new SqlProbe(h2);
        inner.afterMapping = probe::preparedSelfText;

        DemoServlet outer = new DemoServlet();
        outer.pattern = "/outer";
        outer.afterMapping = () -> {
            inner.service(FakeHttp.request("GET", "/inner/1"), FakeHttp.response()); // forward-like
            assertThat(RequestContextHolder.current()).as("outer context survives inner exit").isNotNull();
            return probe.preparedSelfText();
        };

        outer.service(FakeHttp.request("GET", "/outer"), FakeHttp.response());

        // outer request object carries the pattern of the outermost mapping
        assertThat(SqlTag.parse(outer.seenSql.get(0)).orElseThrow().endpoint()).isEqualTo("GET /outer");
        assertThat(RequestContextHolder.current()).isNull();
    }

    @Test
    void agentFaultNeverBreaksTheBusinessQuery() throws Exception {
        try {
            Fq.install(new io.github.anthonydeseille.flairquery.agent.core.FqRuntime(
                    FqConfig.fromEnv(Map.of("FQ_BASE_PACKAGES", FIXTURE, "FQ_TEST_FAULT", "tagger,request"))));

            DemoServlet servlet = new DemoServlet();
            SqlProbe probe = new SqlProbe(hikari);
            servlet.afterMapping = () -> String.valueOf(probe.selectOne());
            servlet.service(FakeHttp.request("GET", "/x"), FakeHttp.response());

            assertThat(servlet.seenSql).containsExactly("1");
            SqlProbe untagged = new SqlProbe(h2);
            assertThat(untagged.preparedSelfText()).isEqualTo(untagged.lastProbeSql());
            assertThat(Fq.errorCount()).isPositive();
        } finally {
            Fq.install(installation.runtime());
        }
    }
}
