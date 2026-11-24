package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code USELESS_EAGER} (trap 2): a to-one association loaded EAGERly (the JPA default of {@code @ManyToOne} and
 * {@code @OneToOne}) and never read becomes {@code fetch = FetchType.LAZY} on the entity's field. Whether
 * another endpoint now loads it lazily (a new N+1) is the prover's call, not this generator's.
 *
 * <p>Handles field annotations written as {@code @ManyToOne}, {@code @ManyToOne()}, {@code @ManyToOne(optional
 * = false)} or {@code @ManyToOne(fetch = FetchType.EAGER)}, and adds the {@code FetchType} import when the file
 * needs it (same {@code jakarta}/{@code javax} namespace as its other persistence imports).</p>
 */
public final class LazyAssociationFix implements FixGenerator {

    /** {@code @ManyToOne} or {@code @OneToOne}, with its arguments (one level of nested parentheses). */
    static final Pattern TO_ONE = Pattern.compile(
            "@(?:(?:jakarta|javax)\\.persistence\\.)?(ManyToOne|OneToOne)\\b(\\s*\\((?:[^()]|\\([^()]*\\))*\\))?");
    static final Pattern FETCH = Pattern.compile("fetch\\s*=\\s*((?:[\\w.]*\\.)?FetchType\\.)?(EAGER|LAZY)\\b");
    static final Pattern PERSISTENCE_IMPORT = Pattern.compile(
            "(?m)^import\\s+(jakarta|javax)\\.persistence\\.([\\w*]+)\\s*;[ \\t]*\\n");

    @Override
    public String name() {
        return "lazy-association";
    }

    @Override
    public boolean handles(JsonNode bundle) {
        return bundle.path("kind").asText().equals("USELESS_EAGER");
    }

    @Override
    public Result generate(JsonNode bundle, Path app) throws Exception {
        String[] assoc = bundle.path("evidence").path("association").asText("").split("\\.");
        if (assoc.length != 2) return Result.skip("no association in the evidence");
        String owner = assoc[0];
        String field = assoc[1];
        Optional<String> path = Sources.entity(bundle, owner);
        if (path.isEmpty()) return Result.skip("source of entity " + owner + " not found in the bundle");
        String before = Files.readString(app.resolve(path.get()));
        // comments blanked out (same length): javadoc often names the very annotation ({@code @ManyToOne})
        String code = withoutComments(before);

        Matcher declaration = Pattern.compile(
                "(?m)^[ \\t]*(?:(?:private|protected|public|final|transient)\\s+)*[\\w.<>?, ]+\\s+"
                        + Pattern.quote(field) + "\\s*(?:=[^;]*)?;").matcher(code);
        if (!declaration.find()) {
            return Result.skip(owner + "." + field + ": field not found (property access is not handled)");
        }
        // the annotations of the field: between the previous member (or the class body) and the declaration
        int from = Math.max(Math.max(code.lastIndexOf(';', declaration.start()), code.lastIndexOf('}', declaration.start())),
                code.lastIndexOf('{', declaration.start())) + 1;
        Matcher toOne = TO_ONE.matcher(code).region(from, declaration.start());
        if (!toOne.find()) return Result.skip(owner + "." + field + ": no @ManyToOne / @OneToOne on the field");

        String annotation = before.substring(toOne.start(), toOne.end());
        String args = toOne.group(2) == null ? null : before.substring(toOne.start(2), toOne.end(2));
        String replacement;
        if (args == null || args.replaceAll("[\\s()]", "").isEmpty()) {
            replacement = annotation.substring(0, toOne.start(2) < 0 ? annotation.length()
                    : toOne.start(2) - toOne.start()) + "(fetch = FetchType.LAZY)";
        } else {
            Matcher fetch = FETCH.matcher(args);
            if (fetch.find()) {
                if (fetch.group(2).equals("LAZY")) return Result.skip(owner + "." + field + " is already LAZY");
                String qualifier = fetch.group(1) == null ? "FetchType." : fetch.group(1);
                replacement = annotation.substring(0, toOne.start(2) - toOne.start())
                        + args.substring(0, fetch.start()) + "fetch = " + qualifier + "LAZY" + args.substring(fetch.end());
            } else {
                int open = args.indexOf('(');
                replacement = annotation.substring(0, toOne.start(2) - toOne.start())
                        + args.substring(0, open + 1) + "fetch = FetchType.LAZY, " + args.substring(open + 1).stripLeading();
            }
        }
        String after = before.substring(0, toOne.start()) + replacement + before.substring(toOne.end());
        if (replacement.contains("fetch = FetchType.")) after = withFetchTypeImport(after);

        String kind = toOne.group(1);
        return Result.of(new Fix(name(), "lazy-" + slug(owner) + "-" + slug(field),
                owner + "." + field + ": @" + kind + " loaded EAGERly (JPA default) and never read -> fetch = FetchType.LAZY",
                Map.of(path.get(), new Fix.Change(before, after))));
    }

    /** The source with every comment replaced by spaces (newlines kept): offsets stay valid. */
    static String withoutComments(String s) {
        StringBuilder out = new StringBuilder(s);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
                i = j + 1;
            } else if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') out.setCharAt(i++, ' ');
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (; i < end; i++) if (s.charAt(i) != '\n') out.setCharAt(i, ' ');
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** Adds {@code import <ns>.persistence.FetchType;} in order among the persistence imports, when missing. */
    static String withFetchTypeImport(String source) {
        Matcher m = PERSISTENCE_IMPORT.matcher(source);
        String namespace = null;
        int insertAt = -1;
        int lastEnd = -1;
        while (m.find()) {
            if (m.group(2).equals("FetchType") || m.group(2).equals("*")) return source;
            if (namespace == null) namespace = m.group(1);
            if (insertAt < 0 && m.group(2).compareTo("FetchType") > 0) insertAt = m.start();
            lastEnd = m.end();
        }
        if (namespace == null) {
            Matcher pkg = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;[ \\t]*\\n").matcher(source);
            String line = "import jakarta.persistence.FetchType;\n";
            if (!pkg.find()) return line + source;
            return source.substring(0, pkg.end()) + "\n" + line + source.substring(pkg.end());
        }
        int at = insertAt >= 0 ? insertAt : lastEnd;
        return source.substring(0, at) + "import " + namespace + ".persistence.FetchType;\n" + source.substring(at);
    }

    static String slug(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
