package io.github.anthonydeseille.flairquery.analyzer.forge;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Opens or refreshes the pull request of each merge request, idempotently: running it again changes nothing
 * unless the code or the description changed.
 * <ul>
 *   <li>no request yet for the branch: push the branch, open the request, label it;</li>
 *   <li>an open request: push the branch only if its content changed, update the title / description only if
 *       they changed;</li>
 *   <li>a closed request (a reviewer said no) or a merged one: left alone, never reopened or pushed again;</li>
 *   <li>a patch that does not apply on the base branch any more: skipped, with the reason (the fix must be
 *       proven again on the new code).</li>
 * </ul>
 */
public final class Publisher {

    public enum Status { CREATED, UPDATED, UNCHANGED, CLOSED, MERGED, NOT_APPLIED }

    /** A merge request to publish: {@code patch} paths are relative to the source repository. */
    public record Request(String fix, String branch, String title, String body, Path patch) { }

    public record Result(Request request, Status status, String url, String detail) { }

    /**
     * @param strip     leading components of the patch's paths to remove ({@code a/} and the source app directory)
     * @param directory the application's directory in the target repository
     */
    public record Options(String base, int strip, String directory, boolean draft, List<String> labels) { }

    private final Forge forge;
    private final GitBranches git;
    private final Options options;
    private final Consumer<String> log;

    public Publisher(Forge forge, GitBranches git, Options options, Consumer<String> log) {
        this.forge = forge;
        this.git = git;
        this.options = options;
        this.log = log;
    }

    public Result publish(Request r) throws Exception {
        List<Forge.PullRequest> pulls = forge.pulls(r.branch());
        Forge.PullRequest latest = pulls.isEmpty() ? null : pulls.get(0);
        if (latest != null && !latest.open()) {
            return done(r, latest.merged() ? Status.MERGED : Status.CLOSED, latest.url(), latest.merged()
                    ? "already merged" : "closed by a reviewer: not reopened (delete the branch's requests to propose it again)");
        }
        String message = r.title() + "\n\nProven by FlairQuery (fix " + r.fix() + "): the pull request describes the problem,"
                + " the proof and the regression test.\n";
        String notApplied = git.prepare(r.branch(), r.patch(), options.strip(), options.directory(), message);
        if (notApplied != null) return done(r, Status.NOT_APPLIED, latest == null ? null : latest.url(), notApplied);
        boolean codeChanged = !git.sameAsRemote(r.branch());
        if (latest == null) {
            if (codeChanged) git.push(r.branch());
            Forge.PullRequest created = forge.create(r.branch(), options.base(), r.title(), r.body(), options.draft());
            if (!options.labels().isEmpty()) forge.label(created.number(), options.labels());
            return done(r, Status.CREATED, created.url(), "#" + created.number());
        }
        if (codeChanged) git.push(r.branch());
        boolean textChanged = !latest.title().equals(r.title()) || !latest.body().equals(r.body());
        if (textChanged) forge.update(latest.number(), r.title(), r.body());
        if (!codeChanged && !textChanged) return done(r, Status.UNCHANGED, latest.url(), "#" + latest.number());
        return done(r, Status.UPDATED, latest.url(), "#" + latest.number()
                + (codeChanged ? " code pushed" : "") + (textChanged ? " description updated" : ""));
    }

    private Result done(Request r, Status s, String url, String detail) {
        log.accept(String.format("%-11s %-44s %s %s", s, r.fix(), url == null ? "" : url, detail));
        return new Result(r, s, url, detail);
    }
}
