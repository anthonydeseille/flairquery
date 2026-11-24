package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan reading on recorded EXPLAIN (FORMAT JSON) output (no Docker). */
class PlansTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Trap 4 without the index: the primary key gives the order, reviewer_name is a Filter. */
    static final String WITHOUT = """
            {"Node Type":"Limit","Plans":[{"Node Type":"Index Scan","Relation Name":"review","Index Name":"review_pkey",
             "Filter":"((reviewer_name)::text = ($1)::text)"}]}""";
    /** With it: the column is an index condition. */
    static final String WITH = """
            {"Node Type":"Limit","Plans":[{"Node Type":"Sort","Sort Key":["r1_0.id"],"Plans":[
             {"Node Type":"Bitmap Heap Scan","Relation Name":"review","Recheck Cond":"((reviewer_name)::text = ($1)::text)",
              "Plans":[{"Node Type":"Bitmap Index Scan","Index Name":"idx_review_reviewer_name",
                        "Index Cond":"((reviewer_name)::text = ($1)::text)"}]}]}]}""";

    @Test
    void jdbcPlaceholdersBecomeNumberedParameters() {
        assertThat(Plans.dollarParameters("select a from t where b=? and c like (?||?) offset ? rows"))
                .isEqualTo("select a from t where b=$1 and c like ($2||$3) offset $4 rows");
        assertThat(Plans.dollarParameters("select '?' as q, \"a?\" from t where j ??| array[?] and x = ?"))
                .isEqualTo("select '?' as q, \"a?\" from t where j ?| array[$1] and x = $2");
    }

    @Test
    void aFilteredScanOnTheColumnIsAMissingIndex() throws Exception {
        assertThat(Plans.filteredScans(JSON.readTree(WITHOUT), "review", "reviewer_name")).hasSize(1);
        assertThat(Plans.filteredScans(JSON.readTree(WITH), "review", "reviewer_name")).isEmpty();
        assertThat(Plans.filteredScans(JSON.readTree(WITHOUT), "review", "name")).isEmpty();
        assertThat(Plans.filteredScans(JSON.readTree(WITHOUT), "book", "reviewer_name")).isEmpty();
    }

    @Test
    void shapeNamesNodesRelationsAndIndexesWithoutCosts() throws Exception {
        JsonNode a = JSON.readTree(WITHOUT);
        assertThat(Plans.shape(a)).isEqualTo(
                "Limit (Index Scan on review using review_pkey filter ((reviewer_name)::text = ($1)::text))");
        assertThat(Plans.shape(JSON.readTree(WITH))).contains("Bitmap Index Scan using idx_review_reviewer_name");
        ((com.fasterxml.jackson.databind.node.ObjectNode) a).put("Total Cost", 123.4).put("Plan Rows", 9);
        assertThat(Plans.shape(a)).isEqualTo(Plans.shape(JSON.readTree(WITHOUT)));
    }
}
