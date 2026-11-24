package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code MISSING_INDEX} (trap 4): a new Liquibase changeset that builds the index without locking writes,
 * {@code CREATE INDEX CONCURRENTLY} (so outside a transaction: {@code runInTransaction:false}), with its
 * rollback, included at the end of the master changelog (YAML or XML). The prover then checks that the plan
 * of the finding's statement uses it and that the endpoint gets faster.
 *
 * <p>Flyway and other migration tools are not handled yet (skipped, with the reason).</p>
 */
public final class IndexMigrationFix implements FixGenerator {

    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern YAML_INCLUDE = Pattern.compile("(?m)^([ \\t]*)-[ \\t]*include:[ \\t]*\\n([ \\t]+)file:[ \\t]*['\"]?([^'\"\\n]+?)['\"]?[ \\t]*$");
    static final Pattern XML_INCLUDE = Pattern.compile("<include\\s+[^>]*file\\s*=\\s*\"([^\"]+)\"[^>]*/>");
    static final Pattern NUMBERED = Pattern.compile("^(\\d+)[-_].*");
    static final int MAX_IDENTIFIER = 63;

    @Override
    public String name() {
        return "index-migration";
    }

    @Override
    public boolean handles(JsonNode bundle) {
        return bundle.path("kind").asText().equals("MISSING_INDEX");
    }

    @Override
    public Result generate(JsonNode bundle, Path app) throws Exception {
        JsonNode ev = bundle.path("evidence");
        String table = ev.path("table").asText();
        String column = ev.path("column").asText();
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) {
            return Result.skip("table / column not a plain identifier: " + table + "." + column);
        }
        List<String> migrations = Sources.withRole(bundle, "migration");
        String master = migrations.stream().filter(IndexMigrationFix::isLiquibaseMaster).findFirst().orElse(null);
        if (master == null) {
            return Result.skip("no Liquibase master changelog among the migrations " + migrations
                    + " (Flyway is not handled yet)");
        }
        String masterText = Files.readString(app.resolve(master));
        boolean yaml = !master.endsWith(".xml");

        // where the included changelogs live, and how the master names them (classpath-relative)
        Matcher include = (yaml ? YAML_INCLUDE : XML_INCLUDE).matcher(masterText);
        String lastInclude = null;
        String yamlItemIndent = "  ";
        String yamlFileIndent = "      ";
        while (include.find()) {
            lastInclude = include.group(yaml ? 3 : 1);
            if (yaml) {
                yamlItemIndent = include.group(1);
                yamlFileIndent = include.group(2);
            }
        }
        if (lastInclude == null) return Result.skip(master + ": no include to follow");
        String includeDir = lastInclude.contains("/") ? lastInclude.substring(0, lastInclude.lastIndexOf('/') + 1) : "";
        // the included file is a classpath resource: find it among the migrations by its name, else next to the master
        String includedName = lastInclude.substring(lastInclude.lastIndexOf('/') + 1);
        Path changelogDir = migrations.stream().filter(m -> m.endsWith("/" + includedName) || m.equals(includedName))
                .findFirst().map(m -> app.resolve(m).getParent()).orElse(app.resolve(master).getParent());

        String index = identifier("idx_" + table + "_" + column);
        int number = nextNumber(changelogDir);
        String prefix = String.format(Locale.ROOT, "%03d", number);
        String fileName = prefix + "-index-" + table.toLowerCase(Locale.ROOT).replace('_', '-') + "-"
                + column.toLowerCase(Locale.ROOT).replace('_', '-') + ".sql";
        String changeset = """
                --liquibase formatted sql

                --changeset flairquery:%s-index-%s-%s runInTransaction:false
                --comment: %s (FlairQuery finding %s). CONCURRENTLY: writes to %s are not blocked while the index builds.
                CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON %s (%s);
                --rollback DROP INDEX CONCURRENTLY IF EXISTS %s;
                """.formatted(prefix, table, column, oneLine(bundle.path("title").asText()), bundle.path("id").asText(),
                table, index, table, column, index);

        String newMaster;
        if (yaml) {
            String entry = yamlItemIndent + "- include:\n" + yamlFileIndent + "file: " + includeDir + fileName + "\n";
            newMaster = masterText + (masterText.endsWith("\n") ? "" : "\n") + entry;
        } else {
            int close = masterText.lastIndexOf("</databaseChangeLog>");
            if (close < 0) return Result.skip(master + ": no </databaseChangeLog>");
            int lineStart = masterText.lastIndexOf('\n', close) + 1;
            String entry = "    <include file=\"" + includeDir + fileName + "\"/>\n";
            newMaster = masterText.substring(0, lineStart) + entry + masterText.substring(lineStart);
        }

        Map<String, Fix.Change> files = new LinkedHashMap<>();
        files.put(relative(app, changelogDir.resolve(fileName)), new Fix.Change(null, changeset));
        files.put(master, new Fix.Change(masterText, newMaster));
        return Result.of(new Fix(name(), "index-" + LazyAssociationFix.slug(table) + "-" + LazyAssociationFix.slug(column),
                table + "." + column + ": " + index + " built CONCURRENTLY by a new Liquibase changeset ("
                        + bundle.path("endpoint").asText() + " scans " + table + " and filters on " + column + ")",
                files));
    }

    static boolean isLiquibaseMaster(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.contains("master") && (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml"));
    }

    /** One more than the highest {@code NNN-} prefix of the changelog directory. */
    static int nextNumber(Path dir) throws IOException {
        int max = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Matcher m = NUMBERED.matcher(f.getFileName().toString());
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max + 1;
    }

    static String identifier(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.length() <= MAX_IDENTIFIER ? lower : lower.substring(0, MAX_IDENTIFIER);
    }

    private static String relative(Path app, Path file) {
        return app.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
