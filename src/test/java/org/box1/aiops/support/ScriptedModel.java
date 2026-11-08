package org.box1.aiops.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;

import org.box1.aiops.model.ChatRequest;
import org.box1.aiops.model.ChatResult;
import org.box1.aiops.model.ModelClient;
import org.box1.aiops.model.ModelRequest;
import org.box1.aiops.model.ModelResult;
import org.box1.aiops.model.ToolCall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.Mock;

/**
 * Deterministic model. By default it echoes the evidence: one MEDIUM finding citing each OK item, and the service's
 * UNAVAILABLE items as unknowns. Tests can replace the behaviour with {@link #script}.
 */
@Mock
@ApplicationScoped
public class ScriptedModel implements ModelClient {
    public static final List<ModelRequest> REQUESTS = new CopyOnWriteArrayList<>();
    public static volatile Function<ModelRequest, ModelResult> script;
    /** Chat calls (D-016), in order. */
    public static final List<ChatRequest> CHAT_REQUESTS = new CopyOnWriteArrayList<>();
    /** Chat behaviour; by default the model answers in text without calling a tool. */
    public static volatile Function<ChatRequest, ChatResult> chatScript;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void reset() {
        REQUESTS.clear();
        script = null;
        CHAT_REQUESTS.clear();
        chatScript = null;
    }

    public static ModelResult content(String content) {
        return new ModelResult(ModelResult.Outcome.OK, content, "stop", 1000, 200, null);
    }

    @Override
    public String modelId() {
        return "scripted-test-model";
    }

    public static ChatResult text(String answer) {
        return new ChatResult(ModelResult.Outcome.OK, answer, List.of(), "stop", 500, 50, null);
    }

    public static ChatResult tools(ToolCall... calls) {
        return new ChatResult(ModelResult.Outcome.OK, null, List.of(calls), "tool_calls", 500, 30, null);
    }

    public static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        CHAT_REQUESTS.add(request);
        Function<ChatRequest, ChatResult> s = chatScript;
        return s != null ? s.apply(request) : text("I can answer that without reading anything.");
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        REQUESTS.add(request);
        Function<ModelRequest, ModelResult> s = script;
        return s != null ? s.apply(request) : echo(request);
    }

    public static ModelResult echo(ModelRequest request) {
        try {
            String user = request.user();
            JsonNode root = JSON.readTree(user.substring(user.indexOf('\n') + 1));
            ObjectNode out = JSON.createObjectNode();
            out.put("summary", "Reviewed " + root.path("incident").path("type").asText() + " evidence.");
            ArrayNode findings = out.putArray("findings");
            ArrayNode unknowns = out.putArray("unknowns");
            for (JsonNode e : root.path("evidence")) {
                if ("OK".equals(e.path("retrievalStatus").asText()) && findings.size() < 10) {
                    ObjectNode f = findings.addObject();
                    f.put("statement", e.path("type").asText() + " evidence was retrieved");
                    f.putArray("evidenceRefs").add(e.path("ref").asText());
                    f.put("confidence", "MEDIUM");
                }
            }
            if (unknowns.isEmpty()) {
                unknowns.add("No additional unknowns from the model.");
            }
            ArrayNode actions = out.putArray("suggestedActions");
            actions.addObject().put("type", "ESCALATE_TO_OPERATOR").put("description", "Review the order")
                .put("expectedEffect", "An operator checks the evidence").put("rollback", "None needed");
            return content(JSON.writeValueAsString(out));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
