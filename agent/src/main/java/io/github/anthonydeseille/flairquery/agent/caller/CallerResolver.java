package io.github.anthonydeseille.flairquery.agent.caller;

import io.github.anthonydeseille.flairquery.sql.SqlTag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the application frame that triggered a SQL statement: the first frame whose class belongs to
 * {@code FQ_BASE_PACKAGES}, skipping generated proxies (Spring CGLIB, Hibernate, ByteBuddy, JDK proxies,
 * lambdas). Without base packages, the first frame outside well-known framework packages is used.
 *
 * <p>The stack walk is lazy and stops at the first match. Formatted, sanitized results are cached per
 * (class, method, line) with a bounded cache.
 */
public final class CallerResolver {

    static final int MAX_CACHE = 10_000;
    static final int MAX_DEPTH = 512;

    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.hibernate.", "org.apache.", "org.postgresql.", "com.zaxxer.",
            "org.h2.", "com.mysql.", "oracle.", "org.mariadb.", "com.microsoft.sqlserver.",
            "org.eclipse.", "io.micrometer.", "io.opentelemetry.", "org.aspectj.", "net.bytebuddy.",
            "org.junit.", "kotlin.", "scala.", "com.fasterxml.", "reactor.", "io.netty.", "org.liquibase.",
            "liquibase.", "org.flywaydb.", "io.github.anthonydeseille.flairquery.agent.",
            "io.github.anthonydeseille.flairquery.shaded.", "io.github.anthonydeseille.flairquery.sql.");

    private final StackWalker walker = StackWalker.getInstance();
    private final List<String> basePackages;
    private final Map<FrameKey, String> cache = new ConcurrentHashMap<>();

    public CallerResolver(List<String> basePackages) {
        this.basePackages = basePackages.stream().map(p -> p.endsWith(".") ? p : p + ".").toList();
    }

    public String resolve() {
        StackWalker.StackFrame frame = walker.walk(frames -> frames
                .limit(MAX_DEPTH)
                .filter(f -> isCandidate(f.getClassName()))
                .findFirst()
                .orElse(null));
        if (frame == null) {
            return SqlTag.UNKNOWN;
        }
        FrameKey key = new FrameKey(frame.getClassName(), frame.getMethodName(), frame.getLineNumber());
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String formatted = SqlTag.sanitize(format(key));
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(key, formatted);
        return formatted;
    }

    boolean isCandidate(String className) {
        if (isGenerated(className)) {
            return false;
        }
        if (!basePackages.isEmpty()) {
            for (String p : basePackages) {
                if (className.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }
        for (String p : FRAMEWORK_PREFIXES) {
            if (className.startsWith(p)) {
                return false;
            }
        }
        return true;
    }

    static boolean isGenerated(String className) {
        return className.contains("$$")                 // Spring CGLIB, lambdas, Javassist
                || className.contains("$HibernateProxy")
                || className.contains("$ByteBuddy$")
                || className.contains("$Proxy")             // com.sun.proxy.$Proxy12, jdk.proxy2.$Proxy7
                || className.startsWith("jdk.proxy");
    }

    static String format(FrameKey k) {
        String line = k.line() > 0 ? ":" + k.line() : "";
        return k.className() + "#" + k.methodName() + line;
    }

    record FrameKey(String className, String methodName, int line) { }
}
