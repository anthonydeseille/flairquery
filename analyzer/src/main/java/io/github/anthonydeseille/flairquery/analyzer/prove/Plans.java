package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plans of parameterized statements on a proof database, without parameter values:
 * {@code EXPLAIN (GENERIC_PLAN)} (PostgreSQL 16+). Used to reproduce database-side findings the agent cannot
 * see (a missing index) and to tell a real plan change from measurement noise.
 */
final class Plans {

    static final ObjectMapper JSON = new ObjectMapper();

    private Plans() { }

    /** Root plan node, empty when the statement cannot be explained (unknown parameter types...). */
    static Optional<JsonNode> explain(Connection c, String sql) {
        try {
            return Optional.of(explainOrThrow(c, sql));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Needs a simple-protocol connection ({@link ProofDatabase#connectForPlans}). */
    static JsonNode explainOrThrow(Connection c, String sql) throws Exception {
        String statement = dollarParameters(sql.strip().replaceAll(";\\s*$", ""));
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN (GENERIC_PLAN, FORMAT JSON) " + statement)) {
            if (!rs.next()) throw new IllegalStateException("EXPLAIN returned nothing");
            return JSON.readTree(rs.getString(1)).path(0).path("Plan");
        }
    }

    /**
     * JDBC {@code ?} placeholders to {@code $1, $2...}, outside quoted literals and identifiers. JDBC escapes a
     * literal question mark operator as {@code ??} (jsonb {@code ?|}): that one stays a {@code ?}.
     */
    static String dollarParameters(String sql) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                out.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
            } else if (c == '?' && i + 1 < sql.length() && sql.charAt(i + 1) == '?') {
                out.append('?');
                i++;
            } else if (c == '?') {
                out.append('$').append(++n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Shape of a plan: node types, relations and indexes, nested; no costs, no row estimates. Two equal shapes
     * do the same work.
     */
    static String shape(JsonNode node) {
        StringBuilder out = new StringBuilder(node.path("Node Type").asText());
        if (node.has("Relation Name")) out.append(" on ").append(node.path("Relation Name").asText());
        if (node.has("Index Name")) out.append(" using ").append(node.path("Index Name").asText());
        if (node.has("Join Type")) out.append(" ").append(node.path("Join Type").asText());
        if (node.has("Sort Key")) out.append(" by ").append(node.path("Sort Key"));
        if (node.has("Filter")) out.append(" filter ").append(node.path("Filter").asText());
        if (node.has("Index Cond")) out.append(" cond ").append(node.path("Index Cond").asText());
        JsonNode children = node.path("Plans");
        if (children.isArray() && !children.isEmpty()) {
            out.append(" (");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(shape(children.get(i)));
            }
            out.append(')');
        }
        return out.toString();
    }

    /**
     * Scans of {@code table} that keep rows through an equality filter on {@code column} (the column is in the
     * {@code Filter}, not in an index condition): what a missing index looks like.
     */
    static List<JsonNode> filteredScans(JsonNode node, String table, String column) {
        List<JsonNode> out = new ArrayList<>();
        Pattern equality = Pattern.compile("(?<![\\w.])\\(?(?:\\w+\\.)?" + Pattern.quote(column)
                + "\\b\\)?(?:::[\\w ]+)?\\s*=\\s*\\(?(?:\\$\\d+|\\?|'[^']*')");
        collect(node, table, equality, out);
        return out;
    }

    private static void collect(JsonNode node, String table, Pattern equality, List<JsonNode> out) {
        if (node.path("Relation Name").asText().equals(table) && equality.matcher(node.path("Filter").asText()).find()) {
            out.add(node);
        }
        for (JsonNode child : node.path("Plans")) collect(child, table, equality, out);
    }

    /**
     * {@code MISSING_INDEX} reproduced on a proof database: the finding (from its bundle) when the plan of its
     * statement still filters the column in a scan, empty when an index serves it.
     */
    record MissingIndex(String id, JsonNode finding) {

        static MissingIndex of(JsonNode bundle) {
            ObjectNode f = JSON.createObjectNode();
            for (String k : List.of("kind", "endpoint", "title", "severity")) f.set(k, bundle.get(k));
            f.set("evidence", bundle.path("evidence").deepCopy());
            return new MissingIndex(bundle.path("id").asText(), f);
        }

        String sql() {
            return finding.path("evidence").path("sql").asText();
        }

        /**
         * The finding with the plan seen on this database, empty when the column is no longer filtered by a scan.
         * A statement that cannot be explained keeps the finding: never "gone" without a plan to show it.
         */
        Optional<JsonNode> check(Connection c) {
            JsonNode ev = finding.path("evidence");
            Optional<JsonNode> plan = explain(c, sql());
            if (plan.isPresent() && filteredScans(plan.get(), ev.path("table").asText(), ev.path("column").asText()).isEmpty()) {
                return Optional.empty();
            }
            ObjectNode f = finding.deepCopy();
            ((ObjectNode) f.path("evidence")).put("plan", plan.map(Plans::shape).orElse("(cannot be explained)"));
            return Optional.of(f);
        }

        /** Plan shape for the report (before / after). */
        String plan(Connection c) {
            try {
                return shape(explainOrThrow(c, sql()));
            } catch (Exception e) {
                return "(cannot be explained: " + e.getMessage() + ")";
            }
        }
    }
}
