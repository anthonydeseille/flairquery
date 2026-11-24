package io.github.anthonydeseille.flairquery.analyzer.prove;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * What one run of the application showed: the response of every replayed request, the cost of every endpoint
 * per request (from the agent's {@code /fq/profile}, measured rounds only) and the agent's findings by id.
 *
 * @param responses by request path, in replay order
 * @param endpoints by endpoint ({@code GET /books})
 * @param findings  by finding id ({@link io.github.anthonydeseille.flairquery.analyzer.bundle.BundleBuilder#findingId})
 */
public record Measurement(Map<String, Response> responses, Map<String, EndpointCost> endpoints,
                          Map<String, JsonNode> findings) {

    /** @param json parsed body, null when the body is not JSON */
    public record Response(int status, JsonNode json) { }

    /**
     * Average per request over the measured rounds.
     *
     * @param statements what the endpoint runs: one {@code "<per request> x <sql>"} entry per statement, sorted
     *                   (from the agent's profile); two runs with equal lists ran exactly the same SQL
     */
    public record EndpointCost(long requests, double statementsPerRequest, double sqlMsPerRequest,
                               List<String> statements) {

        public EndpointCost(long requests, double statementsPerRequest, double sqlMsPerRequest) {
            this(requests, statementsPerRequest, sqlMsPerRequest, List.of());
        }
    }
}
