package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

/**
 * Turns one evidence bundle into a fix. Deterministic generators are templates; the LLM generator (step 4)
 * implements the same interface. A generator never decides that its fix is good: the prover does.
 */
public interface FixGenerator {

    /** Outcome: a fix, or why none is proposed (unsupported shape, already fixed...). */
    record Result(Fix fix, String skipped) {
        static Result of(Fix fix) {
            return new Result(fix, null);
        }

        static Result skip(String why) {
            return new Result(null, why);
        }
    }

    String name();

    /** Whether this generator handles the bundle's kind of finding. */
    boolean handles(JsonNode bundle);

    /**
     * @param bundle evidence bundle ({@link io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder})
     * @param app    the application directory; bundle source paths are relative to it, files are read from disk
     */
    Result generate(JsonNode bundle, Path app) throws Exception;
}
