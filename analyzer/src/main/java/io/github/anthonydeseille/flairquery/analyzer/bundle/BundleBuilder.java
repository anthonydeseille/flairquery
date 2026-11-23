package io.github.anthonydeseille.flairquery.analyzer.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.code.SourceLocator;
import io.github.anthonydeseille.flairquery.analyzer.db.DatabaseFindings;
import io.github.anthonydeseille.flairquery.analyzer.input.JsonSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One evidence bundle per finding: the finding and its evidence (agent JPA side, or collector database side),
 * the database view of the endpoint (statements, callers, plans), and the source files a fix would touch, each
 * with its role (entity, caller, repository, migration, configuration). Deterministic: no LLM here.
 */
public final class BundleBuilder {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_FILE_CHARS = 40_000;
    static final int MAX_STATEMENTS = 10;

    private final JsonSource agent;
    private final JsonSource collector;
    private final SourceLocator sources;
    private final String window;

    public BundleBuilder(JsonSource agent, JsonSource collector, SourceLocator sources, String window) {
        this.agent = agent;
        this.collector = collector;
        this.sources = sources;
        this.window = window;
    }

    public List<ObjectNode> build() throws Exception {
        List<ObjectNode> findings = new ArrayList<>();
        if (agent != null) {
            for (JsonNode f : agent.get("/fq/findings").path("findings")) {
                ObjectNode copy = f.deepCopy();
                copy.put("source", "agent");
                findings.add(copy);
            }
        }
        JsonNode queries = JSON.createArrayNode();
        if (collector != null) {
            findings.addAll(DatabaseFindings.of(collector, window));
            queries = collector.get("/fq/collector/queries?window=" + window + "&order=time&limit=200").path("queries");
        }
        JsonNode model = agent == null ? JSON.createObjectNode() : agent.get("/fq/model");
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode f : findings) {
            out.add(bundle(f, queries, model));
        }
        return out;
    }

    ObjectNode bundle(ObjectNode f, JsonNode queries, JsonNode model) {
        String kind = f.path("kind").asText();
        String endpoint = f.path("endpoint").asText();
        JsonNode ev = f.path("evidence");
        ObjectNode b = JSON.createObjectNode();
        b.put("id", id(kind, endpoint, subject(kind, ev)));
        b.put("kind", kind);
        b.put("severity", f.path("severity").asText());
        b.put("endpoint", endpoint);
        b.put("title", f.path("title").asText());
        b.put("origin", f.path("source").asText("agent"));
        b.put("fixHint", f.path("fix").asText());
        b.set("evidence", ev.deepCopy());

        // database view of the endpoint: its statements, their cost and callers
        ArrayNode statements = b.putObject("database").putArray("statements");
        List<String> callers = new ArrayList<>();
        int n = 0;
        for (JsonNode q : queries) {
            if (!runsFor(q, endpoint) || n++ >= MAX_STATEMENTS) continue;
            ObjectNode s = statements.addObject();
            s.put("sql", q.path("sql").asText());
            s.put("calls", q.path("calls").asLong());
            s.put("meanTimeMs", q.path("meanTimeMs").asDouble());
            s.put("totalTimeMs", q.path("totalTimeMs").asDouble());
            s.put("rows", q.path("rows").asLong());
            s.put("tempBlksWritten", q.path("tempBlksWritten").asLong());
            for (JsonNode a : q.path("attribution")) {
                if (a.path("endpoint").asText().equals(endpoint)) {
                    s.put("caller", a.path("caller").asText());
                    s.put("executions", a.path("executions").asLong());
                    callers.add(a.path("caller").asText());
                }
            }
        }

        // source files a fix would touch
        Map<Path, ObjectNode> files = new LinkedHashMap<>();
        switch (kind) {
            case "N_PLUS_ONE_LAZY", "USELESS_EAGER" -> {
                String[] assoc = ev.path("association").asText("").split("\\.");
                if (assoc.length == 2) {
                    String owner = entity(assoc[0], model);
                    addClass(files, owner, "entity", null);
                    addTarget(files, model, owner, assoc[1]);
                    sources.repositoriesOf(owner).forEach(r -> addClass(files, r, "repository", null));
                }
                addCaller(files, ev.path("caller").asText(null));
            }
            case "OVER_FETCHING" -> {
                String entity = ev.path("entity").asText();
                addClass(files, entity, "entity", null);
                sources.repositoriesOf(entity).forEach(r -> addClass(files, r, "repository", null));
            }
            case "OPEN_IN_VIEW" -> {
                for (JsonNode l : ev.path("lazyLoadsOutsideTransaction")) addCaller(files, l.path("caller").asText(null));
                sources.configuration().forEach(c -> addFile(files, c, "configuration", null, null));
            }
            case "MISSING_INDEX" -> {
                sources.entityOfTable(ev.path("table").asText()).ifPresent(e -> addClass(files, e, "entity", null));
                addCaller(files, ev.path("caller").asText(null));
                sources.migrations().forEach(m -> addFile(files, m, "migration", null, null));
            }
            case "SORT_SPILL" -> {
                for (JsonNode t : ev.path("tables")) {
                    sources.entityOfTable(t.asText()).ifPresent(e -> addClass(files, e, "entity", null));
                }
                addCaller(files, ev.path("caller").asText(null));
                sources.migrations().forEach(m -> addFile(files, m, "migration", null, null));
            }
            default -> { }
        }
        for (String c : callers) addCaller(files, c);
        ArrayNode src = b.putArray("sources");
        files.values().forEach(src::add);
        return b;
    }

    private static boolean runsFor(JsonNode q, String endpoint) {
        for (JsonNode a : q.path("attribution")) {
            if (a.path("endpoint").asText().equals(endpoint)) return true;
        }
        return endpoint.equals(q.path("endpoint").asText());
    }

    /** Entity class name from a simple name, through the agent model first, then the source tree. */
    private String entity(String simple, JsonNode model) {
        for (JsonNode e : model.path("entities")) {
            String fqn = e.path("entity").asText();
            if (fqn.endsWith("." + simple) || fqn.equals(simple)) return fqn;
        }
        return sources.classNamed(simple).orElse(simple);
    }

    private void addTarget(Map<Path, ObjectNode> files, JsonNode model, String owner, String attribute) {
        for (JsonNode e : model.path("entities")) {
            if (!e.path("entity").asText().equals(owner)) continue;
            for (JsonNode a : e.path("attributes")) {
                if (a.path("name").asText().equals(attribute) && a.hasNonNull("target")) {
                    addClass(files, a.path("target").asText(), "entity", null);
                }
            }
        }
    }

    private void addCaller(Map<Path, ObjectNode> files, String caller) {
        sources.caller(caller).ifPresent(c -> {
            addFile(files, c.file(), "caller", c.className(), c.line() > 0 ? c.line() : null);
            // the repositories the caller uses: where a fetch plan or a projection would go
            sources.repositoriesUsedBy(c.file()).forEach(r -> addClass(files, r, "repository", null));
        });
    }

    private void addClass(Map<Path, ObjectNode> files, String className, String role, Integer line) {
        sources.locate(className).ifPresent(p -> addFile(files, p, role, className, line));
    }

    private void addFile(Map<Path, ObjectNode> files, Path file, String role, String className, Integer line) {
        ObjectNode existing = files.get(file);
        if (existing != null) {
            ArrayNode roles = (ArrayNode) existing.get("roles");
            boolean present = false;
            for (JsonNode r : roles) present |= r.asText().equals(role);
            if (!present) roles.add(role);
            if (line != null && !existing.has("line")) existing.put("line", line);
            return;
        }
        ObjectNode s = JSON.createObjectNode();
        s.putArray("roles").add(role);
        if (className != null) s.put("class", className);
        s.put("path", sources.relative(file));
        if (line != null) s.put("line", line);
        String text = sources.read(file);
        s.put("truncated", text.length() > MAX_FILE_CHARS);
        s.put("content", text.length() > MAX_FILE_CHARS ? text.substring(0, MAX_FILE_CHARS) : text);
        files.put(file, s);
    }

    /** What the finding is about: association, entity, table.column... (part of its stable id). */
    static String subject(String kind, JsonNode ev) {
        return switch (kind) {
            case "N_PLUS_ONE_LAZY", "USELESS_EAGER" -> ev.path("association").asText();
            case "OVER_FETCHING" -> ev.path("entity").asText();
            case "MISSING_INDEX" -> ev.path("table").asText() + "." + ev.path("column").asText();
            case "SORT_SPILL" -> ev.path("sortKeys").toString();
            default -> "";
        };
    }

    static String id(String kind, String endpoint, String subject) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((kind + "|" + endpoint + "|" + subject).getBytes(StandardCharsets.UTF_8));
            return kind.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-" + HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            return kind + "-" + Integer.toHexString((endpoint + subject).hashCode());
        }
    }
}
