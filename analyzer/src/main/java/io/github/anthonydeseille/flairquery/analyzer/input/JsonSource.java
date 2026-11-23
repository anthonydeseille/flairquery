package io.github.anthonydeseille.flairquery.analyzer.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Where the analyzer reads JSON from: a live agent / collector over HTTP, or recorded files (tests, offline). */
@FunctionalInterface
public interface JsonSource {

    ObjectMapper JSON = new ObjectMapper();

    /** @param path path and query, e.g. {@code /fq/findings} */
    JsonNode get(String path) throws Exception;

    static JsonSource http(String baseUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = baseUrl.replaceAll("/+$", "");
        return path -> {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode() + " on " + base + path);
            }
            return JSON.readTree(r.body());
        };
    }

    /** Recorded responses: path (without query) -> file; unknown paths return an empty object. */
    static JsonSource files(Map<String, Path> byPath, Function<String, Path> fallback) {
        return path -> {
            String key = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            Path file = byPath.get(path) != null ? byPath.get(path) : byPath.get(key);
            if (file == null && fallback != null) file = fallback.apply(path);
            return file == null || !Files.exists(file) ? JSON.createObjectNode() : JSON.readTree(file.toFile());
        };
    }
}
