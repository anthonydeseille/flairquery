package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.analyzer.fix.FixCommand;
import io.github.anthonydeseille.flairquery.analyzer.fix.GeneratorsTestAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 step 2 on the real demo app: the regression tests of the three merge requests (traps 2, 4 and 1) are
 * proven red on the current code and green with their fix, by Maven on copies of the demo (its own Testcontainers
 * setup). The tests are the ones Claude wrote and {@link LlmTestIT} proved, frozen in
 * {@code src/test/resources/regression-tests} so that {@code make verify} makes no API call; the template fixes are
 * generated from the recorded bundles, trap 1's fix is the frozen LLM patch. When the demo changes and a test no
 * longer proves, regenerate it with {@code make llm-it}. Needs Docker.
 */
class RegressionTestIT {

    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path WORK = REPO.resolve("analyzer/target/regression-tests-it");
    static final Path TESTS = Path.of("src/test/resources/regression-tests").toAbsolutePath();
    static final Path FIXES = Path.of("src/test/resources/fixes").toAbsolutePath();

    static final Map<String, TestProof.Verdict> verdicts = new LinkedHashMap<>();

    @BeforeAll
    static void prove() throws Exception {
        Files.createDirectories(WORK);
        Map<String, Path> patches = new LinkedHashMap<>();
        GeneratorsTestAccess.bundles();
        for (FixCommand.Proposal p : FixCommand.generate(
                GeneratorsTestAccess.all(), REPO.resolve("demo-app"),
                "demo-app", FixCommand.generators(), s -> { })) {
            patches.put(p.fix().name(), Files.writeString(WORK.resolve(p.fix().name() + ".patch"), p.patch()));
        }
        patches.put("llm-trap1-authors-list-title-projection", FIXES.resolve("trap1-authors-list-title-projection.patch"));

        TestProof proof = new TestProof.Maven(REPO, "demo-app", WORK.resolve("runs"), List.of(REPO.resolve("mvnw").toString()));
        try (Stream<Path> files = Files.list(TESTS)) {
            for (Path f : files.sorted().toList()) {
                String fix = f.getFileName().toString().split("--")[0];
                RegressionTest test = reference(f);
                verdicts.put(fix, proof.prove(test, patches.get(fix)));
                System.out.println(fix + ": " + verdicts.get(fix));
            }
        }
    }

    /** A frozen test, placed at the path its package and class name give. */
    static RegressionTest reference(Path file) throws Exception {
        String content = Files.readString(file);
        String name = file.getFileName().toString().split("--")[1].replace(".java", "");
        java.util.regex.Matcher pkg = LlmTestGenerator.PACKAGE.matcher(content);
        assertThat(pkg.find()).isTrue();
        String path = "src/test/java/" + pkg.group(1).replace('.', '/') + "/" + name + ".java";
        Object t = LlmTestGenerator.test(REPO.resolve("demo-app"), new ObjectMapper().createObjectNode()
                .put("path", path).put("content", content).put("description", "frozen"));
        assertThat(t).isInstanceOf(RegressionTest.class);
        return (RegressionTest) t;
    }

    @Test
    void trap2UselessEagerTestIsRedThenGreen() {
        assertThat(verdicts.get("lazy-book-publisher").reasons()).isEmpty();
        assertThat(verdicts.get("lazy-book-publisher").red()).contains("Book.publisher");
    }

    @Test
    void trap4MissingIndexTestIsRedThenGreen() {
        assertThat(verdicts.get("index-review-reviewer-name").reasons()).isEmpty();
        assertThat(verdicts.get("index-review-reviewer-name").red()).contains("reviewer_name");
    }

    @Test
    void trap1NPlusOneTestIsRedThenGreen() {
        assertThat(verdicts.get("llm-trap1-authors-list-title-projection").reasons()).isEmpty();
        assertThat(verdicts.get("llm-trap1-authors-list-title-projection").red()).contains("N+1");
    }

    @Test
    void theDemoIsUntouched() throws Exception {
        Process git = new ProcessBuilder("git", "status", "--porcelain", "demo-app").directory(REPO.toFile()).start();
        assertThat(new String(git.getInputStream().readAllBytes())).isEmpty();
    }
}
