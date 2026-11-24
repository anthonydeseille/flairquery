package io.github.anthonydeseille.flairquery.analyzer.forge;

import java.io.IOException;
import java.util.List;

/**
 * The pull / merge requests of a repository on a forge (GitHub now, GitLab later). Branches are pushed with git
 * ({@link GitBranches}); the forge only opens and updates the requests. Nothing is ever merged.
 */
public interface Forge {

    /**
     * @param state  {@code open} or {@code closed}
     * @param merged closed by a merge
     */
    record PullRequest(int number, String state, boolean merged, String url, String title, String body) {
        public boolean open() {
            return state.equals("open");
        }
    }

    /** Every request (open, closed, merged) whose head is {@code branch} of this repository, newest first. */
    List<PullRequest> pulls(String branch) throws IOException, InterruptedException;

    PullRequest create(String branch, String base, String title, String body, boolean draft)
            throws IOException, InterruptedException;

    void update(int number, String title, String body) throws IOException, InterruptedException;

    /** Adds labels (created by the forge when missing). */
    void label(int number, List<String> labels) throws IOException, InterruptedException;
}
