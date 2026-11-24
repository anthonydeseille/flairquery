package io.github.anthonydeseille.flairquery.analyzer.mr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which proven fixes become a merge request, one MR per fix, every MR mergeable on its own in any order:
 * <ol>
 *   <li>only fixes the prover ACCEPTED;</li>
 *   <li>deterministic templates first (they do not vary from run to run), then the fixes resolving the most
 *       findings, then the smallest change (fewer files), then by name;</li>
 *   <li>a fix whose resolved findings are all resolved by fixes already selected is REDUNDANT (not opened, named
 *       in the MR that covers it);</li>
 *   <li>a fix that does not apply on top of the fixes already selected (same lines changed) is DEFERRED: its
 *       proof was made on the current code, so it is regenerated and proven again on the code once the
 *       conflicting MR is merged, never rebased blindly.</li>
 * </ol>
 */
public final class Selection {

    public enum Decision { SELECTED, REDUNDANT, DEFERRED, NOT_PROVEN }

    /**
     * @param related for REDUNDANT the selected fixes covering it, for DEFERRED the selected fixes it conflicts with
     */
    public record Choice(FixEntry fix, Decision decision, List<String> related, String why) { }

    /** The patches selected so far, applied one on top of the other on a copy of the application. */
    public interface Stack {
        /** Applies the patch on top of the previous ones; false (and nothing applied) when it does not apply. */
        boolean push(Path patch) throws IOException, InterruptedException;
    }

    static final Comparator<FixEntry> ORDER = Comparator.comparing(FixEntry::llm)
            .thenComparing(f -> -f.resolves().size())
            .thenComparing(f -> {
                try {
                    return f.files().size();
                } catch (IOException e) {
                    return Integer.MAX_VALUE;
                }
            })
            .thenComparing(FixEntry::name);

    private Selection() { }

    public static List<Choice> select(List<FixEntry> fixes, Stack stack) throws IOException, InterruptedException {
        List<Choice> choices = new ArrayList<>();
        List<FixEntry> proven = new ArrayList<>();
        for (FixEntry f : fixes) {
            if (f.proposed()) proven.add(f);
            else choices.add(new Choice(f, Decision.NOT_PROVEN, List.of(), f.status()));
        }
        proven.sort(ORDER);
        List<FixEntry> selected = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        List<Choice> decided = new ArrayList<>();
        for (FixEntry f : proven) {
            if (!f.resolves().isEmpty() && covered.containsAll(f.resolves())) {
                List<String> by = selected.stream().filter(s -> f.resolves().stream().anyMatch(s.resolves()::contains))
                        .map(FixEntry::name).toList();
                decided.add(new Choice(f, Decision.REDUNDANT, by, "every finding it resolves is resolved by " + String.join(", ", by)));
                continue;
            }
            if (!stack.push(f.patch())) {
                Set<String> files = f.files();
                List<String> with = new ArrayList<>();
                Set<String> shared = new LinkedHashSet<>();
                for (FixEntry s : selected) {
                    Set<String> common = new LinkedHashSet<>(s.files());
                    common.retainAll(files);
                    if (!common.isEmpty()) {
                        with.add(s.name());
                        shared.addAll(common);
                    }
                }
                decided.add(new Choice(f, Decision.DEFERRED, with, with.isEmpty()
                        ? "does not apply on the current code (proven on an older version?)"
                        : "changes the same lines as " + String.join(", ", with)
                                + " (" + String.join(", ", shared.stream().map(Selection::fileName).toList()) + ")"));
                continue;
            }
            selected.add(f);
            covered.addAll(f.resolves());
            decided.add(new Choice(f, Decision.SELECTED, List.of(), "resolves " + f.resolves().size() + " finding(s)"));
        }
        decided.addAll(choices);
        return decided;
    }

    static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
