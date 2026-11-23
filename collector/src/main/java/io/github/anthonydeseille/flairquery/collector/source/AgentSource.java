package io.github.anthonydeseille.flairquery.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.collector.delta.Counters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the FlairQuery agent's per-endpoint counters ({@code GET /fq/endpoints}, cumulative since the agent
 * started): HTTP requests, statements, SQL time and HTTP time. Single service: one agent URL (with several
 * replicas behind a Service, only the replica answering is counted; see the multi-service roadmap).
 */
public final class AgentSource {

    /** Order of the endpoint counters stored in {@link Counters}. */
    public static final List<String> COUNTERS = List.of("requests", "queries", "sql_ms", "http_ms");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoints;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AgentSource(String baseUrl) {
        this.endpoints = URI.create(baseUrl.replaceAll("/+$", "") + "/fq/endpoints");
    }

    public URI uri() {
        return endpoints;
    }

    public record EndpointRow(String app, String endpoint, Counters counters) { }

    public List<EndpointRow> read() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(endpoints).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("agent answered HTTP " + r.statusCode() + " on " + endpoints);
        }
        return parse(r.body());
    }

    public static List<EndpointRow> parse(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        String app = root.path("app").asText("unknown-app");
        List<EndpointRow> out = new ArrayList<>();
        for (JsonNode e : root.path("endpoints")) {
            out.add(new EndpointRow(app, e.path("endpoint").asText(), Counters.of(
                    e.path("requests").asDouble(), e.path("queries").asDouble(),
                    e.path("sqlTimeMs").asDouble(), e.path("httpTimeMs").asDouble())));
        }
        return out;
    }
}
