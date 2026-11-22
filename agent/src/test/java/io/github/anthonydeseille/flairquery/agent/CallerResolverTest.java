package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.caller.CallerResolver;
import io.github.anthonydeseille.flairquery.fixture.AuthorService;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.SuperMethodCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

class CallerResolverTest {

    private static final String FIXTURE = "io.github.anthonydeseille.flairquery.fixture";

    private final CallerResolver resolver = new CallerResolver(List.of(FIXTURE));

    @Test
    void firstFrameInBasePackages() {
        String caller = new AuthorService().findAll(resolver::resolve);

        assertThat(caller).isEqualTo(FIXTURE + ".AuthorService#findAll:" + AuthorService.findAllLine);
    }

    @Test
    void skipsSpringCglibStyleProxies() throws Exception {
        // same naming scheme as Spring 6: Foo$$SpringCGLIB$$0, overriding and calling super
        Class<? extends AuthorService> proxyType = new ByteBuddy()
                .subclass(AuthorService.class)
                .name(FIXTURE + ".AuthorService$$SpringCGLIB$$0")
                .method(named("findAll")).intercept(SuperMethodCall.INSTANCE)
                .make()
                .load(AuthorService.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        AuthorService proxy = proxyType.getDeclaredConstructor().newInstance();

        String caller = proxy.findAll(resolver::resolve);

        assertThat(caller).isEqualTo(FIXTURE + ".AuthorService#findAll:" + AuthorService.findAllLine);
    }

    @Test
    void skipsLambdaFramesButKeepsTheEnclosingMethod() {
        String caller = new AuthorService().viaLambda(resolver::resolve);

        assertThat(caller).startsWith(FIXTURE + ".AuthorService#lambda$viaLambda$");
    }

    @Test
    void skipsJdkDynamicProxies() {
        @SuppressWarnings("unchecked")
        Supplier<String> proxied = (Supplier<String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Supplier.class}, (p, m, a) -> resolver.resolve());

        String caller = new AuthorService().findAll(proxied);

        assertThat(caller).isEqualTo(FIXTURE + ".AuthorService#findAll:" + AuthorService.findAllLine);
    }

    @Test
    void unknownWhenNoFrameMatches() {
        CallerResolver none = new CallerResolver(List.of("com.nowhere"));

        assertThat(none.resolve()).isEqualTo("unknown");
    }

    @Test
    void withoutBasePackagesUsesFirstNonFrameworkFrame() {
        CallerResolver heuristic = new CallerResolver(List.of());

        // test class lives in the agent package (ignored), the fixture is the first non-framework frame
        String caller = new AuthorService().findAll(heuristic::resolve);

        assertThat(caller).isEqualTo(FIXTURE + ".AuthorService#findAll:" + AuthorService.findAllLine);
    }

    @Test
    void resultIsStableAcrossCalls() {
        AuthorService svc = new AuthorService();
        String first = svc.findAll(resolver::resolve);
        String second = svc.findAll(resolver::resolve);

        assertThat(second).isSameAs(first); // served from cache
    }
}
