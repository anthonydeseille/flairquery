package io.github.anthonydeseille.flairquery.agent;

import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup optimization must never hide a class the agent needs to instrument. */
class IgnoreMatcherTest {

    private static boolean ignored(String name) {
        return FqAgent.ignored().matches(new TypeDescription.Latent(name, 0, TypeDescription.Generic.OBJECT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jakarta.servlet.http.HttpServlet",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.FrameworkServlet",
            "org.springframework.jdbc.datasource.SingleConnectionDataSource",
            "org.apache.commons.dbcp2.DelegatingConnection",
            "org.apache.tomcat.jdbc.pool.ConnectionPool",
            "com.mchange.v2.c3p0.impl.NewProxyConnection",
            "com.zaxxer.hikari.pool.HikariProxyConnection",
            "com.zaxxer.hikari.pool.HikariProxyPreparedStatement",
            "org.postgresql.jdbc.PgConnection",
            "org.postgresql.jdbc.PgPreparedStatement",
            "org.h2.jdbc.JdbcConnection",
            "com.mysql.cj.jdbc.ConnectionImpl",
            "oracle.jdbc.driver.T4CConnection",
            "com.acme.app.MyServlet"})
    void instrumentationTargetsAreNeverIgnored(String name) {
        assertThat(ignored(name)).as(name).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "org.springframework.beans.factory.support.DefaultListableBeanFactory",
            "org.springframework.boot.SpringApplication",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "ch.qos.logback.classic.Logger",
            "org.hibernate.internal.SessionImpl",
            "liquibase.database.jvm.JdbcConnection",
            "org.apache.catalina.servlets.DefaultServlet",
            "org.apache.coyote.Request",
            "java.lang.String",
            "io.github.anthonydeseille.flairquery.agent.FqAgent",
            "io.github.anthonydeseille.flairquery.shaded.bytebuddy.ByteBuddy"})
    void irrelevantPackagesAreSkipped(String name) {
        assertThat(ignored(name)).as(name).isTrue();
    }
}
