package io.github.anthonydeseille.flairquery.analyzer.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} on the Claude API, through the official Java SDK (beta messages endpoint, for server-side
 * refusal fallbacks). Credentials as the SDK resolves them ({@code ANTHROPIC_API_KEY}...).
 *
 * <ul>
 *   <li>adaptive thinking (the model's default), depth set by {@code effort};</li>
 *   <li>{@code fallbacks: "default"}: a request declined by the safety classifiers is re-run server side on the
 *       recommended fallback model instead of returning a refusal;</li>
 *   <li>streamed (long outputs: whole source files), accumulated into one message;</li>
 *   <li>the system prompt and tools are cached, and the conversation prefix with them (automatic caching);</li>
 *   <li>each assistant message is appended to the history as returned (thinking blocks included);</li>
 *   <li>tools are {@code strict}: their input always matches the schema.</li>
 * </ul>
 */
public final class ClaudeClient implements LlmClient {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final long maxTokens;

    public ClaudeClient(String model, String effort, long maxTokens) {
        this(AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofMinutes(15)).maxRetries(3).build(),
                model, effort, maxTokens);
    }

    ClaudeClient(AnthropicClient client, String model, String effort, long maxTokens) {
        this.client = client;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.effort = BetaOutputConfig.Effort.of(effort == null || effort.isBlank() ? "high" : effort);
        this.maxTokens = maxTokens;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Session open(String system, List<Tool> tools) {
        List<BetaTool> betaTools = tools.stream().map(ClaudeClient::tool).toList();
        List<BetaMessageParam> history = new ArrayList<>();
        return inputs -> {
            history.add(BetaMessageParam.ofUserOfContentBlockParams(inputs.stream().map(ClaudeClient::block).toList()));
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(system).build()))
                    .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .addBeta(FALLBACK_BETA)
                    .fallbacksDefault()
                    .messages(history);
            betaTools.forEach(params::addTool);
            BetaMessage message = stream(params.build());
            history.add(message.toParam());
            return reply(message);
        };
    }

    private BetaMessage stream(MessageCreateParams params) {
        BetaMessageAccumulator acc = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> events = client.beta().messages().createStreaming(params)) {
            events.stream().forEach(acc::accumulate);
        }
        return acc.message();
    }

    static Reply reply(BetaMessage m) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (BetaContentBlock b : m.content()) {
            b.text().ifPresent(t -> text.append(t.text()));
            b.toolUse().ifPresent(u -> calls.add(new ToolCall(u.id(), u.name(), u._input().convert(JsonNode.class))));
        }
        Stop stop = m.stopReason().map(r -> {
            if (r.equals(BetaStopReason.END_TURN) || r.equals(BetaStopReason.STOP_SEQUENCE)) return Stop.END;
            if (r.equals(BetaStopReason.TOOL_USE)) return Stop.TOOL_USE;
            if (r.equals(BetaStopReason.MAX_TOKENS)) return Stop.MAX_TOKENS;
            if (r.equals(BetaStopReason.REFUSAL)) return Stop.REFUSAL;
            return Stop.OTHER;
        }).orElse(Stop.OTHER);
        BetaUsage u = m.usage();
        Usage usage = new Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L),
                u.cacheCreationInputTokens().orElse(0L));
        return new Reply(stop, text.toString(), calls, m.model().asString(), usage);
    }

    private static BetaContentBlockParam block(Input in) {
        return switch (in) {
            case Text t -> BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(t.text()).build());
            case ToolResult r -> BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                    .toolUseId(r.callId()).content(r.content()).isError(r.error()).build());
        };
    }

    /** A JSON Schema object ({@code properties}, {@code required}) as a strict SDK tool. */
    static BetaTool tool(Tool t) {
        BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
        for (Iterator<Map.Entry<String, JsonNode>> it = t.inputSchema().path("properties").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            props.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
        }
        List<String> required = new ArrayList<>();
        t.inputSchema().path("required").forEach(r -> required.add(r.asText()));
        return BetaTool.builder()
                .name(t.name())
                .description(t.description())
                .strict(true)
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }
}
