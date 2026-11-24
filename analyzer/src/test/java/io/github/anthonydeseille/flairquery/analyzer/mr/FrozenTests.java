package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * The regression tests Claude wrote and {@link LlmTestIT} proved for the demo's merge requests, frozen in
 * {@code src/test/resources/regression-tests/<fix>--<Class>.java} so that {@code make verify} makes no API call
 * ({@code FixIT} proves them red / green again). Regenerate them with {@code make llm-it} when the demo changes.
 */
public final class FrozenTests {

    public static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    public static final Path TESTS = Path.of("src/test/resources/regression-tests").toAbsolutePath();
    public static final Path FIXES = Path.of("src/test/resources/fixes").toAbsolutePath();

    private FrozenTests() { }

    /** A generator serving the frozen test of each fix (none for a fix without one), instead of the LLM. */
    public static RegressionTests.Generator generator() {
        return (fix, patch, report, app, failed) -> {
            try (Stream<Path> files = Files.list(TESTS)) {
                Path f = files.filter(p -> p.getFileName().toString().startsWith(fix.name() + "--")).findFirst().orElse(null);
                return f == null ? new LlmTestGenerator.Result(null, "no frozen test for " + fix.name())
                        : new LlmTestGenerator.Result(reference(f), null);
            }
        };
    }

    /** A frozen test, placed at the path its package and class name give. */
    public static RegressionTest reference(Path file) throws Exception {
        String content = Files.readString(file);
        String name = file.getFileName().toString().split("--")[1].replace(".java", "");
        Matcher pkg = LlmTestGenerator.PACKAGE.matcher(content);
        if (!pkg.find()) throw new IllegalStateException("no package in " + file);
        String path = "src/test/java/" + pkg.group(1).replace('.', '/') + "/" + name + ".java";
        Object t = LlmTestGenerator.test(REPO.resolve("demo-app"), new ObjectMapper().createObjectNode()
                .put("path", path).put("content", content).put("description", "frozen"));
        if (!(t instanceof RegressionTest test)) throw new IllegalStateException(file + ": " + t);
        return test;
    }
}
