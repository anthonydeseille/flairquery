package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.bootstrap.Fq;
import io.github.anthonydeseille.flairquery.agent.bootstrap.FqHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FqFailSafeTest {

    private static final FqHooks EXPLODING = new FqHooks() {
        @Override public String tagSql(String sql) { throw new OutOfMemoryError("boom"); }
        @Override public void statementPrepared(Object st, String sql) { throw new IllegalStateException("boom"); }
        @Override public long queryStart() { throw new IllegalStateException("boom"); }
        @Override public void queryEnd(Object st, String sql, long start, Throwable t) { throw new IllegalStateException("boom"); }
        @Override public void requestEnter(Object r) { throw new IllegalStateException("boom"); }
        @Override public void requestExit(Object r, Object s, Throwable t) { throw new StackOverflowError(); }
        @Override public void entityLoaded(Object e) { throw new IllegalStateException("boom"); }
        @Override public long lazyLoadStart(String k, String r) { throw new IllegalStateException("boom"); }
        @Override public void lazyLoadEnd(String k, String r, long s, Throwable t) { throw new IllegalStateException("boom"); }
        @Override public void inMemoryPagination() { throw new IllegalStateException("boom"); }
        @Override public void transactionEnter() { throw new IllegalStateException("boom"); }
        @Override public void transactionExit() { throw new IllegalStateException("boom"); }
        @Override public void openInView() { throw new IllegalStateException("boom"); }
    };

    @AfterEach
    void reset() {
        Fq.reset();
    }

    @Test
    void facadeNeverThrowsAndReturnsTheOriginalSql() {
        Fq.install(EXPLODING);

        assertThat(Fq.tagSql("select 1")).isEqualTo("select 1");
        assertThatCode(() -> Fq.requestEnter(new Object())).doesNotThrowAnyException();
        assertThatCode(() -> Fq.requestExit(new Object(), null, null)).doesNotThrowAnyException();
        assertThat(Fq.tagSql(null)).isNull();
        assertThatCode(() -> Fq.statementPrepared(new Object(), "select 1")).doesNotThrowAnyException();
        assertThat(Fq.queryStart()).isEqualTo(-1L);
        assertThatCode(() -> Fq.queryEnd(new Object(), null, 1L, null)).doesNotThrowAnyException();
    }

    @Test
    void ormHooksNeverThrowEither() {
        Fq.install(EXPLODING);

        assertThatCode(() -> {
            Fq.entityLoaded(new Object());
            Fq.entityLoaded(null);
            Fq.lazyLoadEnd("collection", "A.b", Fq.lazyLoadStart("collection", "A.b"), null);
            Fq.lazyLoadEnd("collection", "A.b", 1L, null);
            Fq.inMemoryPagination();
            Fq.transactionEnter();
            Fq.transactionExit();
            Fq.openInView();
        }).doesNotThrowAnyException();
        assertThat(Fq.lazyLoadStart("proxy", "A")).isEqualTo(-1L);
        assertThat(Fq.errorCount()).isPositive();
    }

    @Test
    void circuitBreakerDisablesTheAgentAfterTooManyErrors() {
        Fq.install(EXPLODING);

        for (int i = 0; i < Fq.MAX_ERRORS; i++) {
            Fq.tagSql("select 1");
        }

        assertThat(Fq.isActive()).isFalse();
    }

    @Test
    void nullFromHooksMeansUnchanged() {
        Fq.install(new FqHooks() {
            @Override public String tagSql(String sql) { return null; }
            @Override public void statementPrepared(Object st, String sql) { }
            @Override public long queryStart() { return -1L; }
            @Override public void queryEnd(Object st, String sql, long start, Throwable t) { }
            @Override public void requestEnter(Object r) { }
            @Override public void requestExit(Object r, Object s, Throwable t) { }
        });

        assertThat(Fq.tagSql("select 1")).isEqualTo("select 1");
    }

    @Test
    void initFailureLeavesTheApplicationUninstrumented() {
        Instrumentation broken = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException("no instrumentation for you");
                });

        assertThat(FqAgent.start(Map.of(), broken)).isNull();
        assertThat(FqAgent.start(Map.of("FQ_TEST_FAULT", "init"), broken)).isNull();
        assertThat(Fq.isActive()).isFalse();
    }

    @Test
    void disabledInstallsNothing() {
        Instrumentation untouchable = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (p, m, a) -> {
                    throw new AssertionError("FQ_ENABLED=false must not touch Instrumentation: " + m.getName());
                });

        assertThat(FqAgent.start(Map.of("FQ_ENABLED", "false"), untouchable)).isNull();
        assertThat(Fq.isActive()).isFalse();
    }
}
