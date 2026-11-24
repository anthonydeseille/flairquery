package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Outcome of the proof of one patch.
 *
 * @param status  ACCEPTED only when every check passed
 * @param reasons why it was not accepted (empty when accepted)
 * @param report  full report: checks, per-endpoint costs before / after, findings resolved and new
 */
public record Verdict(Status status, List<String> reasons, ObjectNode report) {

    public enum Status {
        /** Every check passed: the fix may be proposed. */
        ACCEPTED,
        /** At least one check failed (or the patch does not apply, build or start). */
        REJECTED,
        /** Nothing can be concluded: the finding does not show up on the test database without the fix. */
        INCONCLUSIVE
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }
}
