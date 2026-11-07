package org.box1.aiops.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks model output after guided decoding (D-009). Structure errors reject the whole answer; a finding without a
 * real citation, or an action outside the allowlist, is dropped and counted.
 */
public final class OutputValidator {
    static final int MAX_FINDINGS = 10;
    static final int MAX_ACTIONS = 5;
    static final int MAX_UNKNOWNS = 10;
    private static final Set<String> CONFIDENCE = Set.of("LOW", "MEDIUM", "HIGH");

    public record Validated(String summary, List<Analysis.Finding> findings, List<String> unknowns,
            List<Analysis.SuggestedAction> actions, Analysis.Quality quality) {
    }

    /** Thrown when the answer as a whole cannot be used. The message is fixed text, never model output. */
    public static class Invalid extends Exception {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private OutputValidator() {
    }

    public static Validated validate(ObjectMapper json, String content, Set<String> knownRefs) throws Invalid {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (Exception e) {
            throw new Invalid("model output is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new Invalid("model output is not a JSON object");
        }
        String summary = string(root.get("summary"), 2000, "summary");
        JsonNode findings = array(root.get("findings"), MAX_FINDINGS, "findings");
        JsonNode unknowns = array(root.get("unknowns"), MAX_UNKNOWNS, "unknowns");
        JsonNode actions = array(root.get("suggestedActions"), MAX_ACTIONS, "suggestedActions");

        List<Analysis.Finding> kept = new ArrayList<>();
        int unsupported = 0;
        for (JsonNode f : findings) {
            if (!f.isObject()) {
                throw new Invalid("finding is not an object");
            }
            String statement = string(f.get("statement"), 1000, "finding.statement");
            String confidence = string(f.get("confidence"), 10, "finding.confidence");
            if (!CONFIDENCE.contains(confidence)) {
                throw new Invalid("finding.confidence is not LOW, MEDIUM or HIGH");
            }
            JsonNode refs = array(f.get("evidenceRefs"), 10, "finding.evidenceRefs");
            List<String> cited = new ArrayList<>();
            boolean allKnown = true;
            for (JsonNode r : refs) {
                if (!r.isTextual() || !knownRefs.contains(r.textValue())) {
                    allKnown = false;
                } else {
                    cited.add(r.textValue());
                }
            }
            if (cited.isEmpty() || !allKnown) {
                unsupported++;
                continue;
            }
            kept.add(new Analysis.Finding(statement, List.copyOf(cited), confidence));
        }
        List<String> unknownList = new ArrayList<>();
        for (JsonNode u : unknowns) {
            unknownList.add(string(u, 500, "unknowns[]"));
        }
        List<Analysis.SuggestedAction> keptActions = new ArrayList<>();
        int dropped = 0;
        for (JsonNode a : actions) {
            if (!a.isObject()) {
                throw new Invalid("suggested action is not an object");
            }
            String type = a.path("type").asText("");
            if (!Prompt.ACTION_TYPES.contains(type)) {
                dropped++;
                continue;
            }
            keptActions.add(new Analysis.SuggestedAction(type, string(a.get("description"), 500, "description"),
                string(a.get("expectedEffect"), 500, "expectedEffect"), string(a.get("rollback"), 500, "rollback"),
                false));
        }
        return new Validated(summary, List.copyOf(kept), List.copyOf(unknownList), List.copyOf(keptActions),
            new Analysis.Quality(kept.size(), unsupported, dropped));
    }

    private static String string(JsonNode node, int max, String name) throws Invalid {
        if (node == null || !node.isTextual()) {
            throw new Invalid(name + " is missing or not a string");
        }
        String s = node.textValue();
        if (s.length() > max) {
            throw new Invalid(name + " is longer than " + max);
        }
        return s;
    }

    private static JsonNode array(JsonNode node, int max, String name) throws Invalid {
        if (node == null || !node.isArray()) {
            throw new Invalid(name + " is missing or not an array");
        }
        if (node.size() > max) {
            throw new Invalid(name + " has more than " + max + " entries");
        }
        return node;
    }
}
