package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lookups in a bundle's {@code sources} (paths relative to the application directory). */
final class Sources {

    private Sources() { }

    /** Source path of the entity named {@code simpleOrQualified}. */
    static Optional<String> entity(JsonNode bundle, String simpleOrQualified) {
        for (JsonNode s : bundle.path("sources")) {
            String cls = s.path("class").asText("");
            if (hasRole(s, "entity") && (cls.equals(simpleOrQualified) || cls.endsWith("." + simpleOrQualified))) {
                return Optional.of(s.path("path").asText());
            }
        }
        return Optional.empty();
    }

    /** Paths of the files with a role, in bundle order. */
    static List<String> withRole(JsonNode bundle, String role) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : bundle.path("sources")) {
            if (hasRole(s, role)) out.add(s.path("path").asText());
        }
        return out;
    }

    private static boolean hasRole(JsonNode source, String role) {
        for (JsonNode r : source.path("roles")) {
            if (r.asText().equals(role)) return true;
        }
        return false;
    }
}
