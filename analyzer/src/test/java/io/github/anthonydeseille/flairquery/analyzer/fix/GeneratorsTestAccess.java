package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** The demo's evidence bundles of {@link GeneratorsTest} (recorded agent + collector responses), for other packages. */
public final class GeneratorsTestAccess {

    private GeneratorsTestAccess() { }

    public static void bundles() throws Exception {
        GeneratorsTest.bundles();
    }

    public static List<JsonNode> all() {
        return GeneratorsTest.bundles.stream().map(b -> (JsonNode) b).toList();
    }
}
