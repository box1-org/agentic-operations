package org.box1.aiops.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;

import org.box1.aiops.model.ModelClient;
import org.box1.aiops.model.ModelRequest;
import org.box1.aiops.model.ModelResult;

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
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void reset() {
        REQUESTS.clear();
        script = null;
    }

    public static ModelResult content(String content) {
        return new ModelResult(ModelResult.Outcome.OK, content, "stop", 1000, 200, null);
    }

    @Override
    public String modelId() {
        return "scripted-test-model";
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
