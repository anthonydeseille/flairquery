package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The regression test of a fix: generated, proven red / green, generated again with the reasons when it is not,
 * up to a bounded number of retries. A fix whose test cannot be proven keeps its merge request, without a test
 * and saying why.
 */
public final class RegressionTests {

    /** Writes a test for a fix (the LLM; a fake in unit tests). */
    public interface Generator {
        LlmTestGenerator.Result generate(FixEntry fix, String patch, JsonNode report, Path app,
                                         List<LlmTestGenerator.Attempt> failed) throws Exception;
    }

    /**
     * @param test     the proven test, null when none
     * @param red      its assertion message on the current code
     * @param attempts tests generated
     * @param missing  why there is no test (null when there is one)
     */
    public record Outcome(RegressionTest test, String red, int attempts, String missing) {
        static Outcome none(String why, int attempts) {
            return new Outcome(null, null, attempts, why);
        }
    }

    private RegressionTests() { }

    public static Outcome of(FixEntry fix, String patch, JsonNode report, Path app, Generator generator, TestProof proof,
                             int retries, Consumer<String> log) throws Exception {
        List<LlmTestGenerator.Attempt> failed = new ArrayList<>();
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            LlmTestGenerator.Result r = generator.generate(fix, patch, report, app, failed);
            if (r.test() == null) return Outcome.none(r.skipped(), attempt);
            if (failed.stream().anyMatch(a -> a.test().content().equals(r.test().content()))) {
                return Outcome.none("the same test was proposed again after being refused", attempt);
            }
            TestProof.Verdict v = proof.prove(r.test(), fix.patch());
            if (v.passed()) {
                log.accept(String.format("%-12s %-44s red then green: %s", "test", fix.name(), v.red()));
                return new Outcome(r.test(), v.red(), attempt, null);
            }
            log.accept(String.format("%-12s %-44s refused: %s", "test", fix.name(), String.join("; ", v.reasons())));
            failed.add(new LlmTestGenerator.Attempt(r.test(), v.reasons()));
        }
        LlmTestGenerator.Attempt last = failed.get(failed.size() - 1);
        return Outcome.none("no test proven red / green in " + failed.size() + " attempt(s); last: "
                + String.join("; ", last.reasons()), failed.size());
    }
}
