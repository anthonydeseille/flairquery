package io.github.anthonydeseille.flairquery.analyzer.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A language model that calls tools, behind an interface: the fix generator never depends on a provider (Claude
 * through the official SDK today, a local model later). A {@link Session} keeps the conversation on the client
 * side, in the provider's own format, so what the model returned is sent back unchanged on the next turn.
 */
public interface LlmClient {

    /** A tool the model may call; {@code inputSchema} is a JSON Schema object. */
    record Tool(String name, String description, JsonNode inputSchema) { }

    /** What the caller sends on a turn: text, or the result of a tool call. */
    sealed interface Input permits Text, ToolResult { }

    record Text(String text) implements Input { }

    record ToolResult(String callId, String content, boolean error) implements Input { }

    record ToolCall(String id, String name, JsonNode input) { }

    enum Stop {
        /** The model is done; {@link Reply#text()} is its answer. */
        END,
        /** The model called tools: answer every {@link Reply#calls() call} with a {@link ToolResult}. */
        TOOL_USE,
        /** The output was cut by the token limit. */
        MAX_TOKENS,
        /** The model (or the provider's safety classifiers, fallbacks included) declined the request. */
        REFUSAL,
        OTHER
    }

    record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                    cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens);
        }
    }

    record Reply(Stop stop, String text, List<ToolCall> calls, String model, Usage usage) {
        public Reply {
            calls = List.copyOf(calls);
        }
    }

    interface Session {
        /** Sends the inputs as the next user turn and returns the model's answer to it. */
        Reply send(List<Input> inputs) throws Exception;
    }

    /** A new conversation with a fixed system prompt and tool set. */
    Session open(String system, List<Tool> tools);

    /** Model name, for reports. */
    String model();
}
