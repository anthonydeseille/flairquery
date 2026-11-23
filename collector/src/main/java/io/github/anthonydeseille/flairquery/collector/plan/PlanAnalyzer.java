package io.github.anthonydeseille.flairquery.collector.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * Reads an auto_explain JSON plan (EXPLAIN FORMAT JSON, without ANALYZE): fingerprint of its shape and a
 * small summary of the signals FlairQuery cares about (sequential scans and their filters, sorts, limits).
 */
public final class PlanAnalyzer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanAnalyzer() { }

    /** Shape of the plan tree (node types, relations, indexes, join types), costs and row estimates ignored. */
    public static String fingerprint(JsonNode plan) {
        StringBuilder sb = new StringBuilder();
        shape(plan, sb);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static void shape(JsonNode node, StringBuilder sb) {
        sb.append('(').append(node.path("Node Type").asText());
        for (String f : new String[]{"Relation Name", "Index Name", "Join Type", "Strategy", "Parent Relationship"}) {
            if (node.has(f)) sb.append('|').append(node.get(f).asText());
        }
        for (JsonNode child : node.path("Plans")) {
            shape(child, sb);
        }
        sb.append(')');
    }

    /**
     * {@code {"nodeTypes":[...], "seqScans":[{"relation":"review","filter":"..."}], "sorts":[{"keys":[...]}],
     * "hasLimit":true, "totalCost":123.4, "planRows":10}}.
     */
    public static ObjectNode summary(JsonNode plan) {
        ObjectNode s = JSON.createObjectNode();
        TreeSet<String> types = new TreeSet<>();
        ArrayNode seqScans = JSON.createArrayNode();
        ArrayNode sorts = JSON.createArrayNode();
        boolean[] limit = {false};
        walk(plan, types, seqScans, sorts, limit);
        ArrayNode t = s.putArray("nodeTypes");
        types.forEach(t::add);
        s.set("seqScans", seqScans);
        s.set("sorts", sorts);
        s.put("hasLimit", limit[0]);
        s.put("totalCost", plan.path("Total Cost").asDouble());
        s.put("planRows", plan.path("Plan Rows").asLong());
        return s;
    }

    private static void walk(JsonNode n, TreeSet<String> types, ArrayNode seqScans, ArrayNode sorts, boolean[] limit) {
        String type = n.path("Node Type").asText();
        types.add(type);
        if (type.equals("Seq Scan")) {
            ObjectNode scan = seqScans.addObject();
            scan.put("relation", n.path("Relation Name").asText());
            if (n.has("Filter")) scan.put("filter", n.get("Filter").asText());
            scan.put("planRows", n.path("Plan Rows").asLong());
        } else if (type.equals("Sort") || type.equals("Incremental Sort")) {
            ObjectNode sort = sorts.addObject();
            sort.set("keys", n.path("Sort Key").deepCopy());
            if (n.has("Sort Method")) sort.put("method", n.get("Sort Method").asText()); // only with log_analyze
        } else if (type.equals("Limit")) {
            limit[0] = true;
        }
        for (JsonNode child : n.path("Plans")) {
            walk(child, types, seqScans, sorts, limit);
        }
    }
}
