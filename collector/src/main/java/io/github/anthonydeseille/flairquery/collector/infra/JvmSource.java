package io.github.anthonydeseille.flairquery.collector.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the JVM and HikariCP signals of one FlairQuery agent ({@code GET /fq/jvm}). */
public final class JvmSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * @param subject  "namespace/pod" when the pod is known, otherwise the agent URL
     * @param workload the pod's workload ("Deployment/demo-app"), null when unknown
     */
    public InfraSample read(String baseUrl, String subject, String workload) throws Exception {
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/fq/jvm");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("agent answered HTTP " + r.statusCode() + " on " + uri);
        }
        return parse(r.body(), subject, workload);
    }

    static InfraSample parse(String body, String subject, String workload) throws Exception {
        JsonNode root = JSON.readTree(body);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app", root.path("app").asText(null));
        payload.put("workload", workload);
        payload.putAll(JSON.convertValue(root.path("jvm"), new TypeReference<Map<String, Object>>() { }));
        return new InfraSample(InfraSample.JVM, subject, payload);
    }
}
