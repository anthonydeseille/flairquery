package io.github.anthonydeseille.flairquery.analyzer.prove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One HTTP request replayed on the application before and after a fix.
 *
 * @param path        path and query, e.g. {@code /books?limit=50}
 * @param compareBody true: the JSON bodies must be strictly identical; false: only the HTTP status is compared
 *                    (endpoints whose body is not deterministic)
 */
public record RequestSpec(String path, boolean compareBody) {

    /**
     * Reads a requests file: one request per line, {@code <path> [json|status]} (json by default), {@code #}
     * comments and blank lines ignored.
     */
    public static List<RequestSpec> read(Path file) throws IOException {
        List<RequestSpec> out = new ArrayList<>();
        int n = 0;
        for (String raw : Files.readAllLines(file)) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (!parts[0].startsWith("/") || parts.length > 2) {
                throw new IllegalArgumentException(file + ":" + n + ": expected '<path> [json|status]', got: " + line);
            }
            String mode = parts.length == 2 ? parts[1] : "json";
            if (!mode.equals("json") && !mode.equals("status")) {
                throw new IllegalArgumentException(file + ":" + n + ": unknown comparison '" + mode + "' (json|status)");
            }
            out.add(new RequestSpec(parts[0], mode.equals("json")));
        }
        if (out.isEmpty()) throw new IllegalArgumentException(file + ": no request");
        return out;
    }
}
