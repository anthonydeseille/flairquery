package io.github.anthonydeseille.flairquery.collector.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.anthonydeseille.flairquery.sql.SqlNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes data values from an auto_explain plan before anything else sees it. auto_explain prints the
 * parameter values of custom plans into expressions ({@code Filter: (reviewer_name = 'Alice Martin')}):
 * every text field except the structural ones (node type, relation, index, join type...) has its string
 * and numeric literals replaced by {@code ?}. fq-store never receives a data value.
 */
public final class PlanMasker {

    /** Text fields that describe the plan structure and never hold a value. */
    static final Set<String> STRUCTURAL = Set.of(
            "Node Type", "Relation Name", "Schema", "Alias", "Index Name", "Join Type", "Strategy",
            "Parent Relationship", "Partial Mode", "Scan Direction", "CTE Name", "Subplan Name", "Function Name",
            "Operation", "Sort Method", "Sort Space Type", "Command", "Async Capable", "Parallel Aware");

    private PlanMasker() { }

    /** Masks in place and returns the same node. */
    public static JsonNode mask(JsonNode plan) {
        if (plan instanceof ObjectNode o) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            o.fields().forEachRemaining(fields::add);
            for (Map.Entry<String, JsonNode> f : fields) {
                JsonNode v = f.getValue();
                if (v.isTextual()) {
                    if (!STRUCTURAL.contains(f.getKey())) o.set(f.getKey(), TextNode.valueOf(maskText(v.asText())));
                } else if (v.isArray() && !f.getKey().equals("Plans")) {
                    ArrayNode a = (ArrayNode) v;
                    for (int i = 0; i < a.size(); i++) {
                        if (a.get(i).isTextual()) a.set(i, TextNode.valueOf(maskText(a.get(i).asText())));
                        else mask(a.get(i));
                    }
                } else {
                    mask(v);
                }
            }
        } else if (plan instanceof ArrayNode a) {
            a.forEach(PlanMasker::mask);
        }
        return plan;
    }

    static String maskText(String expression) {
        return SqlNormalizer.normalize(expression);
    }
}
