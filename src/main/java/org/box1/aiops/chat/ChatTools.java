package org.box1.aiops.chat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.Ids;
import org.box1.aiops.InvalidArgument;
import org.box1.aiops.NotFound;
import org.box1.aiops.Untrusted;
import org.box1.aiops.analysis.Analysis;
import org.box1.aiops.analysis.AnalysisService;
import org.box1.aiops.evidence.EvidenceItem;
import org.box1.aiops.evidence.EvidenceService;
import org.box1.aiops.mcp.ToolAudit;
import org.box1.aiops.model.ToolSpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The tools the Assistant's model may call (D-016): the read-only evidence reads that MCP also offers, plus reading an
 * existing analysis. Nothing here writes, starts work, runs a shell, calls the Kubernetes API or fetches a URL. Every
 * argument is validated against the contract ID patterns before any upstream call; every result is wrapped as
 * untrusted data. The caller has already checked the person's viewer role on its request thread.
 */
@ApplicationScoped
public class ChatTools {

    /** What one tool call produced: the text the model sees and the audit facts the operator sees. */
    public record Outcome(String text, String target, String decision, String retrievalStatus) {
    }

    private record Tool(ToolSpec spec, Function<JsonNode, Result> run) {
    }

    private record Result(String target, Object body, String retrievalStatus) {
    }

    private final EvidenceService evidence;
    private final AnalysisService analyses;
    private final ObjectMapper json;
    private final int maxResultBytes;
    private final Map<String, Tool> tools;

    @Inject
    public ChatTools(EvidenceService evidence, AnalysisService analyses, ObjectMapper json, AiopsConfig config) {
        this.evidence = evidence;
        this.analyses = analyses;
        this.json = json;
        this.maxResultBytes = config.evidence().sourceMaxBytes();
        this.tools = Map.of(
            "get_order_timeline", tool("get_order_timeline",
                "Read-only. The state timeline of one order from order-service, oldest first.",
                props("orderId", "Order ID, ord_..."), List.of("orderId"),
                a -> item(text(a, "orderId"), evidence.reads().orderTimeline(text(a, "orderId")))),
            "get_fulfillment_status", tool("get_fulfillment_status",
                "Read-only. Fulfillment and ERP acknowledgement status for one order.",
                props("orderId", "Order ID, ord_..."), List.of("orderId"),
                a -> item(text(a, "orderId"), evidence.reads().fulfillmentStatus(text(a, "orderId")))),
            "get_inventory_reservation", tool("get_inventory_reservation",
                "Read-only. The order and its inventory reservation (status, expiry).",
                props("orderId", "Order ID, ord_..."), List.of("orderId"),
                a -> item(text(a, "orderId"), evidence.reads().inventoryReservation(text(a, "orderId")))),
            "list_recent_events", tool("list_recent_events",
                "Read-only. Recent domain events from the last hours, newest first, by order or asset key or by "
                    + "correlation ID. An empty list means no recent events, not that nothing happened.",
                recentEventProps(), List.of(), this::recentEvents),
            "get_deployed_revision", tool("get_deployed_revision",
                "Read-only. The Git revision a service reports at /api/info.",
                serviceProps(), List.of("service"),
                a -> item(text(a, "service"), evidence.reads().deployedRevision(text(a, "service")))),
            "get_analysis", tool("get_analysis",
                "Read-only. An earlier incident analysis by ID: status, summary, cited findings, unknowns and "
                    + "proposed actions (never executed).",
                props("analysisId", "Analysis ID, ana_ followed by 32 hex characters"), List.of("analysisId"),
                this::analysis));
    }

    public boolean known(String name) {
        return name != null && tools.containsKey(name);
    }

    /** The specs offered to the model, in a stable order. */
    public List<ToolSpec> specs() {
        return tools.keySet().stream().sorted().map(n -> tools.get(n).spec()).toList();
    }

    /** Runs one tool call. Never throws: every failure becomes an error the model reads as the tool result. */
    public Outcome call(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new Outcome("error: unknown tool. Available tools: " + String.join(", ", tools.keySet().stream()
                .sorted().toList()), "-", "unknown_tool", null);
        }
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank() ? json.createObjectNode()
                : json.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            return new Outcome("error: arguments must be a JSON object", "invalid", "invalid_argument", null);
        }
        if (!args.isObject()) {
            return new Outcome("error: arguments must be a JSON object", "invalid", "invalid_argument", null);
        }
        try {
            Result r = tool.run().apply(args);
            return new Outcome(render(r.body()), ToolAudit.loggableId(r.target()), "ok", r.retrievalStatus());
        } catch (InvalidArgument e) {
            return new Outcome("error: " + e.code() + ": " + e.getMessage(), "invalid", "invalid_argument", null);
        } catch (NotFound e) {
            return new Outcome("error: " + e.code() + ": " + e.getMessage(), targetOf(args), "not_found", "NOT_FOUND");
        } catch (RuntimeException e) {
            return new Outcome("error: the read failed inside box1; say it could not be checked", targetOf(args),
                "error", "ERROR");
        }
    }

    // ---- tool bodies -------------------------------------------------------------------------------------------

    private Result recentEvents(JsonNode a) {
        String key = text(a, "key");
        String correlation = text(a, "correlationId");
        Integer limit = a.path("limit").isInt() ? a.path("limit").intValue() : null;
        if (key != null && !key.isBlank()) {
            return item(key, evidence.reads().recentEvents(key, limit));
        }
        if (correlation != null && !correlation.isBlank()) {
            return item(null, evidence.reads().recentEventsByCorrelation(correlation, limit));
        }
        throw new InvalidArgument("INVALID_KEY", "give key (ord_... or asset_...) or correlationId");
    }

    private Result analysis(JsonNode a) {
        String id = text(a, "analysisId");
        Analysis a2 = analyses.getAuthorized(id);
        // Evidence is listed without its raw data: the model sees what was cited and how it was retrieved.
        ObjectNode view = json.valueToTree(a2);
        ArrayNode ev = view.putArray("evidence");
        for (EvidenceItem i : a2.evidence()) {
            ev.addObject().put("ref", i.ref()).put("type", i.type()).put("source", i.source())
                .put("retrievalStatus", i.retrievalStatus()).put("detail", i.detail());
        }
        return new Result(id, view, "OK");
    }

    private static Result item(String target, EvidenceItem item) {
        return new Result(target, item, item.retrievalStatus());
    }

    /** The untrusted envelope; an oversized result keeps its newest data entries, or drops its data, to fit. */
    private String render(Object body) {
        JsonNode tree = json.valueToTree(body);
        String text = write(Untrusted.wrap(json, tree));
        if (text.length() <= maxResultBytes || !(tree instanceof ObjectNode obj) || !obj.has("data")) {
            return text.length() <= maxResultBytes ? text
                : write(Untrusted.wrap(json, json.createObjectNode().put("truncated", true)
                    .put("detail", "result too large to show; ask a narrower question")));
        }
        JsonNode data = obj.get("data");
        obj.put("truncated", true);
        if (data instanceof ArrayNode arr) {
            while (arr.size() > 1) {
                for (int i = 0, half = arr.size() / 2; i < half; i++) {
                    arr.remove(0);
                }
                text = write(Untrusted.wrap(json, obj));
                if (text.length() <= maxResultBytes) {
                    return text;
                }
            }
        }
        obj.remove("data");
        return write(Untrusted.wrap(json, obj));
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool result could not be encoded");
        }
    }

    private static String text(JsonNode a, String field) {
        JsonNode v = a.get(field);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static String targetOf(JsonNode a) {
        for (String f : List.of("orderId", "analysisId", "key", "service")) {
            String v = text(a, f);
            if (v != null) {
                return ToolAudit.loggableId(v);
            }
        }
        return "-";
    }

    // ---- specs -------------------------------------------------------------------------------------------------

    private Tool tool(String name, String description, ObjectNode properties, List<String> required,
            Function<JsonNode, Result> run) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = schema.putArray("required");
        required.forEach(req::add);
        schema.put("additionalProperties", false);
        return new Tool(new ToolSpec(name, description, schema), run);
    }

    private ObjectNode props(String name, String description) {
        ObjectNode p = json.createObjectNode();
        p.putObject(name).put("type", "string").put("description", description);
        return p;
    }

    private ObjectNode recentEventProps() {
        ObjectNode p = json.createObjectNode();
        p.putObject("key").put("type", "string").put("description", "Order ID (ord_...) or asset ID (asset_...)");
        p.putObject("correlationId").put("type", "string").put("description", "Correlation ID, instead of key");
        p.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 50)
            .put("description", "How many events, default 20");
        return p;
    }

    private ObjectNode serviceProps() {
        ObjectNode p = json.createObjectNode();
        ObjectNode s = p.putObject("service");
        s.put("type", "string").put("description", "Service name");
        ArrayNode e = s.putArray("enum");
        Ids.SERVICES.stream().sorted().forEach(e::add);
        return p;
    }
}
