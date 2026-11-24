package io.github.anthonydeseille.flairquery.analyzer.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the application's source files from what the findings name: classes (entity, caller), callers with
 * their line ({@code com.x.AuthorController#toDto:47}), the repositories of an entity, the entity mapped to a
 * table, the database migrations and the application configuration. Text-based (package declaration + file
 * name), no compilation: the analyzer reads the source tree as it is.
 */
public final class SourceLocator {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern REPOSITORY = Pattern.compile(
            "interface\\s+(\\w+)\\s+extends\\s+[\\w.]*(?:Jpa|Crud|PagingAndSorting|ListCrud|ListPagingAndSorting)?Repository\\s*<\\s*([\\w.]+)\\s*,");
    private static final Pattern TABLE = Pattern.compile("@Table\\s*\\(\\s*(?:name\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern TYPE_REFERENCE = Pattern.compile("\\b([A-Z]\\w*Repository)\\b");
    public static final Set<String> SKIPPED_DIRS = Set.of("target", "build", ".git", ".gradle", ".idea", "node_modules", "out");

    private final Path root;
    private final Map<String, Path> byClass = new LinkedHashMap<>();
    private final Map<Path, String> content = new LinkedHashMap<>();
    private final List<Path> resources = new ArrayList<>();

    public SourceLocator(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(this.root)) {
            files.filter(p -> !skipped(p)).filter(Files::isRegularFile).forEach(this::index);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the source tree " + root, e);
        }
    }

    private boolean skipped(Path p) {
        for (Path part : root.relativize(p)) {
            if (SKIPPED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private void index(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".java")) {
            String text = read(file);
            Matcher m = PACKAGE.matcher(text);
            String pkg = m.find() ? m.group(1) + "." : "";
            byClass.put(pkg + name.substring(0, name.length() - 5), file);
        } else if (file.toString().contains("/src/main/resources/")) {
            resources.add(file);
        }
    }

    public Path root() { return root; }

    public String relative(Path file) {
        return root.relativize(file).toString();
    }

    public String read(Path file) {
        return content.computeIfAbsent(file, f -> {
            try {
                return Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    /** Source file of a class (nested classes resolve to their top-level file). */
    public Optional<Path> locate(String className) {
        if (className == null) return Optional.empty();
        String c = className;
        while (true) {
            Path p = byClass.get(c);
            if (p != null) return Optional.of(p);
            int dollar = c.lastIndexOf('$');
            if (dollar < 0) return Optional.empty();
            c = c.substring(0, dollar);
        }
    }

    /** Class by simple name (first match), for names given without their package. */
    public Optional<String> classNamed(String simpleName) {
        for (String fqn : byClass.keySet()) {
            if (fqn.equals(simpleName) || fqn.endsWith("." + simpleName)) return Optional.of(fqn);
        }
        return Optional.empty();
    }

    public record Caller(String className, String method, int line, Path file) { }

    /** {@code com.x.AuthorController#toDto:47} -> its file and line, when the class is in the tree. */
    public Optional<Caller> caller(String caller) {
        if (caller == null || !caller.contains("#")) return Optional.empty();
        String cls = caller.substring(0, caller.indexOf('#'));
        String rest = caller.substring(caller.indexOf('#') + 1);
        String method = rest.contains(":") ? rest.substring(0, rest.indexOf(':')) : rest;
        int line = -1;
        if (rest.contains(":")) {
            try {
                line = Integer.parseInt(rest.substring(rest.indexOf(':') + 1));
            } catch (NumberFormatException ignored) {
                // no line
            }
        }
        int l = line;
        return locate(cls).map(f -> new Caller(cls, method, l, f));
    }

    /** Spring Data repositories whose domain type is {@code entity} (simple or qualified name). */
    public List<String> repositoriesOf(String entity) {
        String simple = entity.substring(entity.lastIndexOf('.') + 1);
        List<String> out = new ArrayList<>();
        byClass.forEach((fqn, file) -> {
            Matcher m = REPOSITORY.matcher(read(file));
            while (m.find()) {
                String domain = m.group(2);
                if (domain.equals(simple) || domain.equals(entity)) out.add(fqn);
            }
        });
        return out;
    }

    /** Repositories referenced by a source file (fields, constructor parameters). */
    public List<String> repositoriesUsedBy(Path file) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TYPE_REFERENCE.matcher(read(file));
        while (m.find()) {
            classNamed(m.group(1)).ifPresent(out::add);
        }
        return new ArrayList<>(out);
    }

    /** Entity class mapped to a table ({@code @Table(name = "review")}, or the class name). */
    public Optional<String> entityOfTable(String table) {
        for (Map.Entry<String, Path> e : byClass.entrySet()) {
            String text = read(e.getValue());
            if (!text.contains("@Entity")) continue;
            Matcher m = TABLE.matcher(text);
            String mapped = m.find() ? m.group(1) : e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            if (mapped.equalsIgnoreCase(table)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** Liquibase / Flyway migrations, master changelog first. */
    public List<Path> migrations() {
        List<Path> out = new ArrayList<>();
        for (Path r : resources) {
            String s = r.toString();
            if (s.contains("/db/changelog/") || s.contains("/db/migration/") || r.getFileName().toString().startsWith("changelog")) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Boolean.compare(!a.getFileName().toString().contains("master"), !b.getFileName().toString().contains("master")));
        return out;
    }

    /** application.yml / application.properties (profile-specific files included). */
    public List<Path> configuration() {
        return resources.stream().filter(r -> r.getFileName().toString().startsWith("application")).toList();
    }
}
