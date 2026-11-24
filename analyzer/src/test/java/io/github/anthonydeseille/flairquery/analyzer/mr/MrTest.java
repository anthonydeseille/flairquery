package io.github.anthonydeseille.flairquery.analyzer.mr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.anthonydeseille.flairquery.analyzer.mr.Selection.Decision.DEFERRED;
import static io.github.anthonydeseille.flairquery.analyzer.mr.Selection.Decision.NOT_PROVEN;
import static io.github.anthonydeseille.flairquery.analyzer.mr.Selection.Decision.REDUNDANT;
import static io.github.anthonydeseille.flairquery.analyzer.mr.Selection.Decision.SELECTED;
import static org.assertj.core.api.Assertions.assertThat;

class MrTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of(System.getProperty("fq.repo", "..")).toAbsolutePath().normalize();
    static final Path REFERENCES = Path.of("src/test/resources/fixes").toAbsolutePath();

    @TempDir
    Path tmp;

    /** A proven fix (or a rejected one when {@code resolves} is null) whose patch touches {@code files}. */
    FixEntry fix(String name, String generator, List<String> resolves, String... files) throws Exception {
        StringBuilder patch = new StringBuilder();
        for (String f : files) patch.append("--- a/").append(f).append("\n+++ b/").append(f).append("\n");
        Path p = Files.writeString(tmp.resolve(name + ".patch"), patch);
        return new FixEntry(name, generator, name + " description", resolves == null ? List.of("x") : resolves, 1, p,
                resolves == null ? "REJECTED" : "ACCEPTED", resolves != null, resolves == null ? List.of() : resolves, null);
    }

    /** A stack where the patches named in {@code conflicting} do not apply. */
    static Selection.Stack stack(Set<String> conflicting, List<String> pushed) {
        return patch -> {
            String name = patch.getFileName().toString().replace(".patch", "");
            if (conflicting.contains(name)) return false;
            pushed.add(name);
            return true;
        };
    }

    @Test
    void templatesFirstThenRedundantAndConflictingFixesAreNotOpened() throws Exception {
        List<FixEntry> fixes = List.of(
                fix("llm-small", "llm", List.of("a"), "A.java"),
                fix("llm-big", "llm", List.of("a", "b", "c"), "A.java", "B.java"),
                fix("lazy", "lazy-association", List.of("c"), "C.java"),
                fix("llm-covered", "llm", List.of("b", "c"), "B.java"),
                fix("llm-conflict", "llm", List.of("d"), "B.java", "D.java"),
                fix("rejected", "llm", null, "E.java"));
        List<String> pushed = new ArrayList<>();
        List<Selection.Choice> choices = Selection.select(fixes, stack(Set.of("llm-conflict"), pushed));

        assertThat(choices).extracting(c -> c.fix().name() + ":" + c.decision()).containsExactly(
                "lazy:SELECTED", "llm-big:SELECTED", "llm-covered:REDUNDANT", "llm-small:REDUNDANT",
                "llm-conflict:DEFERRED", "rejected:NOT_PROVEN");
        assertThat(pushed).containsExactly("lazy", "llm-big");
        assertThat(choices.get(2).related()).containsExactly("lazy", "llm-big");
        assertThat(choices.get(4).related()).containsExactly("llm-big");
        assertThat(choices.get(4).why()).isEqualTo("changes the same lines as llm-big (B.java)");
    }

    @Test
    void theReferenceFixesOfTheDemoConflictOnTheirRepositories() throws Exception {
        String n1 = "n-plus-one-lazy-947116bb", oiv = "open-in-view-e4ea07dd", of1 = "over-fetching-b81a3978",
                of2 = "over-fetching-bcb1923c", ue = "useless-eager-952de888";
        List<FixEntry> fixes = new ArrayList<>();
        fixes.add(reference("trap1-authors-list-title-projection", List.of(n1, oiv, of1, of2, ue)));
        fixes.add(reference("trap6-authors-list-dto-projection-no-open-in-view", List.of(n1, oiv, of1, of2, ue)));
        fixes.add(reference("trap3-book-titles-dto-projection", List.of("over-fetching-f5d3f728", "useless-eager-e28d7937")));
        fixes.add(reference("trap5-authors-with-books-page-ids-then-titles",
                List.of("in-memory-pagination-3474fa2c", "over-fetching-b922199d", "over-fetching-fa9cf5c0", "useless-eager-9662f1d7")));
        List<Selection.Choice> choices;
        try (GitStack stack = new GitStack(REPO, "demo-app", tmp.resolve("work"))) {
            choices = Selection.select(fixes, stack);
        }
        assertThat(choices).extracting(c -> c.fix().name() + ":" + c.decision()).containsExactly(
                "llm-trap1-authors-list-title-projection:SELECTED",
                "llm-trap6-authors-list-dto-projection-no-open-in-view:REDUNDANT",
                "llm-trap5-authors-with-books-page-ids-then-titles:DEFERRED",
                "llm-trap3-book-titles-dto-projection:DEFERRED");
        assertThat(choices.get(3).why()).isEqualTo("changes the same lines as llm-trap1-authors-list-title-projection (BookRepository.java)");
        assertThat(tmp.resolve("work")).doesNotExist();
        // the repository itself is untouched
        assertThat(Files.readString(REPO.resolve("demo-app/src/main/java/io/github/anthonydeseille/flairquery/demo/repository/BookRepository.java")))
                .contains("List<Book> findByAuthorIdOrderById(Long authorId);");
    }

    FixEntry reference(String file, List<String> resolves) {
        return new FixEntry("llm-" + file, "llm", file, resolves.subList(0, 1), 1, REFERENCES.resolve(file + ".patch"),
                "ACCEPTED", true, resolves, null);
    }

    static ObjectNode report(boolean schemaChanged) {
        ObjectNode r = JSON.createObjectNode().put("finding", "missing-index-1").put("kind", "MISSING_INDEX")
                .put("endpoint", "GET /reviews/search").put("subject", "review.reviewer_name").put("rounds", 5)
                .put("schemaChanged", schemaChanged);
        r.putArray("findingsResolved").addObject().put("id", "missing-index-1").put("kind", "MISSING_INDEX")
                .put("endpoint", "GET /reviews/search").put("title", "No index for review.reviewer_name | scan");
        r.putArray("checks").addObject().put("check", "identical responses").put("passed", true).put("detail", "7 request(s)");
        var eps = r.putArray("endpoints");
        ObjectNode t = eps.addObject().put("endpoint", "GET /reviews/search").put("target", true).put("sameSql", true);
        t.putObject("before").put("statementsPerRequest", 1.0).put("sqlMsPerRequest", 48.93);
        t.putObject("after").put("statementsPerRequest", 1.0).put("sqlMsPerRequest", 7.15);
        ObjectNode o = eps.addObject().put("endpoint", "GET /books").put("target", false).put("sameSql", true);
        o.putObject("before").put("statementsPerRequest", 45.0).put("sqlMsPerRequest", 220.3);
        o.putObject("after").put("statementsPerRequest", 45.0).put("sqlMsPerRequest", 202.3);
        r.putArray("plans").addObject().put("sql", "select ... where r1_0.reviewer_name=$1")
                .put("before", "Index Scan on review").put("after", "Bitmap Index Scan using idx");
        return r;
    }

    @Test
    void theDescriptionStatesTheProblemTheProofAndWhatToReview() throws Exception {
        FixEntry index = fix("index-review-reviewer-name", "index-migration", List.of("missing-index-1"),
                "demo-app/src/main/resources/db/changelog/002-index.sql", "demo-app/src/main/resources/db/changelog/db.changelog-master.yaml");
        FixEntry deferred = fix("llm-other", "llm", List.of("z"), "demo-app/x/db.changelog-master.yaml");
        List<Selection.Choice> choices = List.of(new Selection.Choice(index, SELECTED, List.of(), ""),
                new Selection.Choice(deferred, DEFERRED, List.of(index.name()), "changes the same lines as index-review-reviewer-name"));
        JsonNode bundle = JSON.createObjectNode().put("id", "missing-index-1").put("severity", "HIGH");

        Description.MergeRequest mr = Description.of(index, report(true), Map.of("missing-index-1", bundle), choices);

        assertThat(mr.branch()).isEqualTo("flairquery/index-review-reviewer-name");
        assertThat(mr.title()).isEqualTo("FlairQuery: missing index on GET /reviews/search (review.reviewer_name)");
        assertThat(mr.body())
                .contains("human review is required")
                .contains("| missing index (target) | `GET /reviews/search` | HIGH | No index for review.reviewer_name \\| scan |")
                .contains("deterministic template `index-migration`")
                .contains("| identical responses | ✅ | 7 request(s) |")
                .contains("| **`GET /reviews/search`** | 1 → 1 | 48.9 → 7.2 |")
                .contains("| `GET /books` | 45 → 45 | 220.3 → 202.3 (same SQL) |")
                .contains("before: Index Scan on review\nafter:  Bitmap Index Scan using idx")
                .contains("Changes the database schema")
                .doesNotContain("LLM-written")
                .contains("`llm-other` waits for this one: changes the same lines as index-review-reviewer-name.");
    }

    @Test
    void anLlmFixChangingTheConfigurationSaysSo() throws Exception {
        FixEntry llm = new FixEntry("llm-x", "llm", "projections", List.of("f"), 2,
                fix("p", "llm", List.of("f"), "demo-app/src/main/resources/application.yml").patch(), "ACCEPTED", true,
                List.of("missing-index-1", "other"), null);
        ObjectNode r = report(false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) r.path("findingsResolved")).addObject().put("id", "other")
                .put("kind", "OPEN_IN_VIEW").put("endpoint", "GET /a").put("title", "t");
        Description.MergeRequest mr = Description.of(llm, r, Map.of(), List.of());
        assertThat(mr.title()).endsWith(", 1 more finding resolved");
        assertThat(mr.body()).contains("Written by an LLM (attempt 2").contains("LLM-written")
                .contains("Changes the application's configuration").contains("(same SQL: noise)")
                .doesNotContain("Related fixes");
    }

    @Test
    void theIndexOfFixesIsReadWithItsPaths() throws Exception {
        Files.writeString(tmp.resolve("index.json"), """
                [ { "name": "a", "generator": "llm", "description": "d", "findings": ["f"], "attempt": 2,
                    "patch": "a.patch", "status": "REJECTED", "proposed": false, "reasons": ["r"] } ]""");
        FixEntry e = FixEntry.read(tmp.resolve("index.json")).get(0);
        assertThat(e.patch()).isEqualTo(tmp.toAbsolutePath().resolve("a.patch"));
        assertThat(e.report()).isNull();
        assertThat(e.resolves()).isEmpty();
        assertThat(e.attempt()).isEqualTo(2);
        assertThat(Selection.select(List.of(e), p -> true)).extracting(Selection.Choice::decision).containsExactly(NOT_PROVEN);
    }

    @Test
    void aFixWithNothingNewIsRedundantEvenWhenItWouldApply() throws Exception {
        List<Selection.Choice> c = Selection.select(List.of(fix("t", "lazy-association", List.of("a"), "A.java"),
                fix("l", "llm", List.of("a"), "Z.java")), p -> true);
        assertThat(c).extracting(Selection.Choice::decision).containsExactly(SELECTED, REDUNDANT);
    }
}
