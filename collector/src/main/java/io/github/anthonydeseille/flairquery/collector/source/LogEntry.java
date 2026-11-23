package io.github.anthonydeseille.flairquery.collector.source;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** One auto_explain entry of the JSON server log: who ran what, its query_id, its own text (tag) and plan. */
public record LogEntry(Instant timestamp, String dbName, String roleName, long queryId, double durationMs,
                       String queryText, JsonNode plan) { }
