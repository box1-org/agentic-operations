package org.box1.aiops.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.box1.aiops.analysis.OutputValidator;
import org.box1.aiops.analysis.Prompt;
import org.box1.aiops.chat.ChatPrompt;
import org.box1.aiops.evidence.EvidenceItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The model client against a real vLLM endpoint, with the production prompts and schemas. Opt-in: set
 * {@code AIOPS_LIVE_MODEL_URL} (e.g. {@code https://<vllm-route>/v1}) and optionally {@code AIOPS_LIVE_MODEL_ID}
 * (default {@code box1-coder}). Model wording is not asserted, only the contract the services rely on.
 */
@EnabledIfEnvironmentVariable(named = "AIOPS_LIVE_MODEL_URL", matches = ".+")
class LiveModelTest {
    static final ObjectMapper JSON = new ObjectMapper();

    final ModelClient model = new LangChain4jModelClient(System.getenv("AIOPS_LIVE_MODEL_URL"),
        System.getenv().getOrDefault("AIOPS_LIVE_MODEL_ID", "box1-coder"), Duration.ofSeconds(10),
        Duration.ofSeconds(120), JSON);

    @Test
    void analysisReturnsSchemaValidJsonCitingTheEvidence() throws Exception {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        ObjectNode status = JSON.createObjectNode().put("orderId", "ord_live1").put("state", "AWAITING_ERP_ACK")
            .put("submittedToErpAt", "2026-11-10T11:20:00Z");
        ObjectNode logs = JSON.createObjectNode();
        logs.putArray("lines").add("2026-11-10T11:20:03Z erp-adapter WARN ERP SOAP call timed out after 30s")
            .add("2026-11-10T11:25:03Z erp-adapter WARN ERP SOAP call timed out after 30s");
        List<EvidenceItem> evidence = List.of(
            new EvidenceItem("ev_1", "fulfillment_status", "order-service", now, now, EvidenceItem.OK, null, null,
                false, status),
            new EvidenceItem("ev_2", "logs", "loki", now, now, EvidenceItem.OK, null, null, false, logs),
            new EvidenceItem("ev_3", "metrics", "thanos", null, now, "UNAVAILABLE", "not configured", null, false,
                null));
        String user = Prompt.user(JSON, "inc_ord_live1", "ERP_ACK_OVERDUE", "ord_live1", 40L, evidence);

        ModelResult r = model.complete(new ModelRequest(Prompt.SYSTEM, user, Prompt.schema(JSON), 2048));

        assertEquals(ModelResult.Outcome.OK, r.outcome(), String.valueOf(r.detail()));
        assertEquals("stop", r.finishReason());
        assertNotNull(r.promptTokens());
        assertNotNull(r.completionTokens());
        OutputValidator.Validated v = OutputValidator.validate(JSON, r.content(), Set.of("ev_1", "ev_2", "ev_3"));
        assertFalse(v.summary().isBlank());
    }

    @Test
    void chatCallsAnOfferedToolThenAnswersFromItsResult() {
        List<ToolSpec> tools = List.of(
            new ToolSpec("get_fulfillment_status", "Read-only. Fulfillment and ERP acknowledgement status for one order.",
                schema("""
                    {"type":"object","properties":{"orderId":{"type":"string","description":"Order ID, ord_..."}},
                    "required":["orderId"],"additionalProperties":false}""")),
            new ToolSpec("search_logs", "Read-only. Log lines for one order ID or one trace ID (give exactly one).",
                schema("""
                    {"type":"object","properties":{"orderId":{"type":"string","description":"Order ID, ord_..."},
                    "traceId":{"type":"string","description":"Trace ID, 32 hex, instead of orderId"}},
                    "required":[],"additionalProperties":false}""")));
        List<ChatMessage> messages = new java.util.ArrayList<>(List.of(ChatMessage.system(ChatPrompt.SYSTEM),
            ChatMessage.user("Has the ERP acknowledged order ord_live1 yet?")));

        ChatResult first = model.chat(new ChatRequest(List.copyOf(messages), tools, 1024));
        assertEquals(ModelResult.Outcome.OK, first.outcome(), String.valueOf(first.detail()));
        assertTrue(first.wantsTools(), "the model asks for a tool before answering");
        ToolCall call = first.toolCalls().get(0);
        assertTrue(Set.of("get_fulfillment_status", "search_logs").contains(call.name()), call.name());
        assertFalse(call.id().isBlank());
        JsonNode args = schema(call.argumentsJson());
        assertEquals("ord_live1", args.path("orderId").asText());

        messages.add(ChatMessage.assistant(first.content(), first.toolCalls()));
        for (ToolCall c : first.toolCalls()) {
            messages.add(ChatMessage.tool(c.id(), "{\"untrusted\":{\"orderId\":\"ord_live1\","
                + "\"state\":\"AWAITING_ERP_ACK\",\"submittedToErpAt\":\"2026-11-10T11:20:00Z\"}}"));
        }
        ChatResult answer = model.chat(new ChatRequest(List.copyOf(messages), List.of(), 1024));
        assertEquals(ModelResult.Outcome.OK, answer.outcome(), String.valueOf(answer.detail()));
        assertFalse(answer.content().isBlank());
        assertTrue(answer.toolCalls().isEmpty());
    }

    static JsonNode schema(String s) {
        try {
            return JSON.readTree(s);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
