package io.github.anthonydeseille.flairquery.analyzer.fix;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A proposed change for one finding, not proven yet.
 *
 * @param generator   what produced it ({@code lazy-association}, {@code index-migration}...)
 * @param name        short file-system safe name ({@code lazy-book-publisher})
 * @param description one line for a human: what changes and why
 * @param files       new content of every changed file, by path relative to the application directory, with
 *                    its previous content ({@code null} for a new file)
 */
public record Fix(String generator, String name, String description, Map<String, Change> files) {

    public record Change(String before, String after) { }

    public Fix {
        files = new LinkedHashMap<>(files);
    }

    /** The patch, paths prefixed with the application directory relative to the repository ({@code demo-app}). */
    public String patch(String appPrefix) {
        String prefix = appPrefix == null || appPrefix.isEmpty() || appPrefix.equals(".") ? ""
                : appPrefix.replace('\\', '/').replaceAll("/+$", "") + "/";
        StringBuilder out = new StringBuilder();
        files.forEach((path, c) -> out.append(UnifiedDiff.of(prefix + path, c.before(), c.after())));
        return out.toString();
    }
}
