package io.github.anthonydeseille.flairquery.analyzer.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anthonydeseille.flairquery.analyzer.llm.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * A fake {@link LlmClient} for unit tests: each session plays a script, one function per turn from what the
 * generator sent to the model's reply. Every session's inputs are recorded.
 */
final class ScriptedLlm implements LlmClient {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Function<Integer, List<Function<List<Input>, Reply>>> scripts;
    final List<List<List<Input>>> sessions = Collections.synchronizedList(new ArrayList<>());

    /** @param scripts the script of the n-th session (0-based) */
    ScriptedLlm(Function<Integer, List<Function<List<Input>, Reply>>> scripts) {
        this.scripts = scripts;
    }

    @Override
    public Session open(String system, List<Tool> tools) {
        List<List<Input>> sent = Collections.synchronizedList(new ArrayList<>());
        List<Function<List<Input>, Reply>> script;
        synchronized (sessions) {
            script = scripts.apply(sessions.size());
            sessions.add(sent);
        }
        return inputs -> {
            sent.add(inputs);
            if (sent.size() > script.size()) throw new AssertionError("no more turns in the script");
            return script.get(sent.size() - 1).apply(inputs);
        };
    }

    @Override
    public String model() {
        return "scripted";
    }

    // ------------------------------------------------------------------ replies

    static Reply call(String tool, JsonNode input) {
        return new Reply(Stop.TOOL_USE, "", List.of(new ToolCall("call-" + tool + "-" + input.hashCode(), tool, input)),
                "scripted", new Usage(100, 10, 0, 0));
    }

    static Reply text(Stop stop, String text) {
        return new Reply(stop, text, List.of(), "scripted", new Usage(100, 10, 0, 0));
    }

    static Reply read(String path) {
        return call("read_file", JSON.createObjectNode().put("path", path));
    }

    static Reply propose(String name, String description, String... pathsAndContents) {
        ObjectNode in = JSON.createObjectNode().put("name", name).put("description", description);
        var files = in.putArray("files");
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            files.addObject().put("path", pathsAndContents[i]).put("content", pathsAndContents[i + 1]);
        }
        return call("propose_fix", in);
    }

    static Reply noFix(String reason) {
        return call("no_fix", JSON.createObjectNode().put("reason", reason));
    }

    /** The text of the first input of a turn. */
    static String textOf(List<Input> inputs) {
        return inputs.get(0) instanceof Text t ? t.text() : null;
    }

    static ToolResult resultOf(List<Input> inputs) {
        return (ToolResult) inputs.get(0);
    }
}
