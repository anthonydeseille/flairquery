package io.github.anthonydeseille.flairquery.analyzer.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database-side findings derived from the collector API (the agent only sees the JPA side), in the same shape
 * as the agent's findings:
 * <ul>
 *   <li>{@code MISSING_INDEX}: a scan discards rows through an equality filter on a selective column</li>
 *   <li>{@code SORT_SPILL}: a statement writes temporary blocks and its plan sorts</li>
 * </ul>
 */
public final class DatabaseFindings {

    static final ObjectMapper JSON = new ObjectMapper();

    /** {@code (reviewer_name)::text = (?)::text}, {@code name = ?}, {@code (b.author_id = ?)}: the column name. */
    static final Pattern EQUALITY = Pattern.compile("\\(?(?:\\w+\\.)?(\\w+)\\)?(?:::[\\w ]+)?\\s*=\\s*\\(?\\?");

    /** A column is selective when an equality on one value matches less than this fraction of the rows. */
    static final double SELECTIVE = 0.01;

    private DatabaseFindings() { }

    public static List<ObjectNode> of(JsonSource collector, String window) throws Exception {
        List<ObjectNode> out = new ArrayList<>();
        JsonNode queries = collector.get("/fq/collector/queries?window=" + window + "&order=time&limit=200").path("queries");
        JsonNode columns = collector.get("/fq/collector/columns").path("columns");
        for (JsonNode q : queries) {
            String endpoint = q.path("endpoint").asText(null);
            if (endpoint == null || endpoint.equals("none")) continue;
            JsonNode plans = collector.get("/fq/collector/plans?queryid=" + q.path("queryid").asText()).path("plans");
            missingIndex(q, plans, columns).ifPresent(out::add);
            sortSpill(q, plans).ifPresent(out::add);
        }
        return out;
    }

    static java.util.Optional<ObjectNode> missingIndex(JsonNode q, JsonNode plans, JsonNode columns) {
        for (JsonNode plan : plans) {
            JsonNode s = plan.path("summary");
            List<JsonNode> scans = new ArrayList<>();
            s.path("filteredScans").forEach(scans::add);
            s.path("seqScans").forEach(scans::add);
            for (JsonNode scan : scans) {
                String table = scan.path("relation").asText();
                String filter = scan.path("filter").asText("");
                Matcher m = EQUALITY.matcher(filter);
                while (m.find()) {
                    String column = m.group(1);
                    JsonNode stats = column(columns, table, column);
                    if (stats == null || stats.path("rowsPerValue").isMissingNode() || stats.path("rowsPerValue").isNull()) continue;
                    double perValue = stats.path("rowsPerValue").asDouble();
                    double rows = stats.path("estimatedRows").asDouble();
                    if (rows <= 0 || perValue >= SELECTIVE * rows) continue;
                    ObjectNode f = finding(q, "MISSING_INDEX", "HIGH",
                            "No index for " + table + "." + column + ": every call scans " + table + " and keeps ~"
                                    + Math.round(perValue) + " of " + Math.round(rows) + " rows",
                            "Add an index on " + table + " (" + column + ") through a migration "
                                    + "(CREATE INDEX CONCURRENTLY, e.g. a Liquibase changeset with runInTransaction:false)");
                    ObjectNode ev = (ObjectNode) f.get("evidence");
                    ev.put("table", table);
                    ev.put("column", column);
                    ev.put("scan", scan.path("node").asText("Seq Scan") + (scan.has("index") ? " on " + scan.path("index").asText() : ""));
                    ev.put("filter", filter);
                    ev.put("rowsPerValue", perValue);
                    ev.put("estimatedRows", Math.round(rows));
                    return java.util.Optional.of(f);
                }
            }
        }
        return java.util.Optional.empty();
    }

    static java.util.Optional<ObjectNode> sortSpill(JsonNode q, JsonNode plans) {
        if (q.path("tempBlksWritten").asLong() <= 0) return java.util.Optional.empty();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        for (JsonNode plan : plans) {
            for (JsonNode sort : plan.path("summary").path("sorts")) sort.path("keys").forEach(k -> keys.add(k.asText()));
            for (JsonNode scan : plan.path("summary").path("seqScans")) tables.add(scan.path("relation").asText());
        }
        if (keys.isEmpty()) return java.util.Optional.empty();
        ObjectNode f = finding(q, "SORT_SPILL", "HIGH",
                "Sort spilling to disk: " + q.path("tempBlksWritten").asLong() + " temporary blocks written for "
                        + q.path("calls").asLong() + " calls (sort on " + String.join(", ", keys) + ")",
                "Serve the order from an index on the sort keys and paginate by key (keyset) instead of OFFSET;"
                        + " sizing work_mem comes after the code fix (phase 6)");
        ObjectNode ev = (ObjectNode) f.get("evidence");
        ArrayNode k = ev.putArray("sortKeys");
        keys.forEach(k::add);
        ArrayNode t = ev.putArray("tables");
        tables.forEach(t::add);
        ev.put("tempBlksWritten", q.path("tempBlksWritten").asLong());
        return java.util.Optional.of(f);
    }

    private static ObjectNode finding(JsonNode q, String kind, String severity, String title, String fix) {
        ObjectNode f = JSON.createObjectNode();
        f.put("endpoint", q.path("endpoint").asText());
        f.put("kind", kind);
        f.put("severity", severity);
        f.put("title", title);
        ObjectNode ev = f.putObject("evidence");
        ev.put("queryid", q.path("queryid").asText());
        ev.put("sql", q.path("sql").asText());
        ev.put("calls", q.path("calls").asLong());
        ev.put("meanTimeMs", q.path("meanTimeMs").asDouble());
        ev.put("caller", callerOf(q));
        f.put("fix", fix);
        f.put("source", "collector");
        return f;
    }

    static String callerOf(JsonNode q) {
        for (JsonNode a : q.path("attribution")) {
            if (a.path("endpoint").asText().equals(q.path("endpoint").asText())) return a.path("caller").asText(null);
        }
        return q.path("caller").asText(null);
    }

    private static JsonNode column(JsonNode columns, String table, String column) {
        for (JsonNode c : columns) {
            String t = c.path("table").asText();
            if ((t.equals(table) || t.endsWith("." + table)) && c.path("column").asText().equals(column)) return c;
        }
        return null;
    }
}
