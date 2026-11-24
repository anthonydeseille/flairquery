package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Turns one evidence bundle into a fix. Deterministic generators are templates; the LLM generator (step 4)
 * implements the same interface. A generator never decides that its fix is good: the prover does.
 */
public interface FixGenerator {

    /** Outcome: a fix, or why none is proposed (unsupported shape, already fixed...). */
    record Result(Fix fix, String skipped) {
        public static Result of(Fix fix) {
            return new Result(fix, null);
        }

        public static Result skip(String why) {
            return new Result(null, why);
        }
    }

    /** A fix the prover rejected, with its patch and the prover's reasons. */
    record Attempt(Fix fix, String patch, List<String> reasons) { }

    String name();

    /** Whether this generator handles the bundle's kind of finding. */
    boolean handles(JsonNode bundle);

    /**
     * @param bundle evidence bundle ({@link io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder})
     * @param app    the application directory; bundle source paths are relative to it, files are read from disk
     */
    Result generate(JsonNode bundle, Path app) throws Exception;

    /**
     * Another fix for the same findings after the prover rejected earlier ones (oldest first). Templates always
     * produce the same change: only a generator that can use the reasons overrides this.
     */
    default Result retry(List<JsonNode> bundles, Path app, List<Attempt> rejected) throws Exception {
        return Result.skip(name() + " cannot learn from a rejection");
    }
}
