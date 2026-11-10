package org.box1.aiops.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.box1.aiops.model.ChatMessage;
import org.box1.aiops.model.ChatRequest;
import org.box1.aiops.model.ChatResult;
import org.box1.aiops.model.ModelClient;
import org.box1.aiops.model.ModelRequest;
import org.box1.aiops.model.ModelResult;
import org.box1.aiops.model.ToolCall;
import org.box1.aiops.model.ToolSpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/** The Langfuse shape of the AI spans, against an in-memory OpenTelemetry SDK. */
class AiTracingTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final InMemorySpanExporter exported = InMemorySpanExporter.create();
    SdkTracerProvider provider;

    @BeforeEach
    void sdk() {
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build();
    }

    @AfterEach
    void close() {
        provider.close();
    }

    AiTracing tracing(boolean content) {
        return new AiTracing(provider.get("test"), JSON, content, "abc123", "test");
    }

    static final AiTracing.TraceInfo INFO = new AiTracing.TraceInfo("answer-message", "otto", "conv_1",
        List.of("assistant"), Map.of("turn_id", "turn_1"));

    static String attr(SpanData s, String key) {
        return s.getAttributes().get(AttributeKey.stringKey(key));
    }

    SpanData span(String name) {
        return exported.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no span " + name));
    }

    /** A model that answers with one tool call, or fails. */
    static ModelClient model(ModelResult.Outcome outcome) {
        return new ModelClient() {
            @Override
            public String modelId() {
                return "box1-coder";
            }

            @Override
            public ModelResult complete(ModelRequest request) {
                return outcome == ModelResult.Outcome.OK
                    ? new ModelResult(outcome, "{\"summary\":\"s\"}", "stop", 1200, 300, null)
                    : ModelResult.failure(outcome, "model endpoint HTTP 503");
            }

            @Override
            public ChatResult chat(ChatRequest request) {
                return new ChatResult(ModelResult.Outcome.OK, null,
                    List.of(new ToolCall("c1", "get_order_timeline", "{\"orderId\":\"ord_1\"}")), "tool_calls", 900, 40,
                    null);
            }
        };
    }

    @Test
    void generationCarriesModelUsageParametersAndTheConversation() throws Exception {
        AiTracing t = tracing(true);
        Span root = t.startTrace("answer-message", AiTracing.Type.AGENT, INFO);
        try (Scope ignored = t.context(root, INFO).makeCurrent()) {
            new TracedModelClient(model(ModelResult.Outcome.OK), t, "vllm").chat(new ChatRequest(List.of(
                ChatMessage.system("policy"), ChatMessage.user("where is ord_1?"),
                ChatMessage.assistant(null, List.of(new ToolCall("c0", "get_order", "{\"orderId\":\"ord_1\"}"))),
                ChatMessage.tool("c0", "{\"untrusted\":{}}")),
                List.of(new ToolSpec("get_order_timeline", "d", JSON.createObjectNode())), 256));
        }
        root.end();

        SpanData g = span("generate-reply");
        assertEquals(root.getSpanContext().getSpanId(), g.getParentSpanId(), "the generation nests under the agent");
        assertEquals("generation", attr(g, "langfuse.observation.type"));
        assertEquals("box1-coder", attr(g, "gen_ai.request.model"));
        assertEquals("vllm", attr(g, "gen_ai.provider.name"));
        assertEquals(900L, g.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(40L, g.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(256L, g.getAttributes().get(AttributeKey.longKey("gen_ai.request.max_tokens")));
        assertEquals(0.0, g.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.temperature")));
        assertEquals(List.of("tool_calls"),
            g.getAttributes().get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));

        JsonNode input = JSON.readTree(attr(g, "langfuse.observation.input"));
        assertEquals(4, input.size(), "the exact messages sent");
        assertEquals("assistant", input.path(2).path("role").asText());
        assertEquals("{\"orderId\":\"ord_1\"}", input.path(2).path("tool_calls").path(0).path("function")
            .path("arguments").asText(), "arguments stay a JSON-encoded string");
        assertEquals("c0", input.path(3).path("tool_call_id").asText());
        JsonNode output = JSON.readTree(attr(g, "langfuse.observation.output"));
        assertEquals("get_order_timeline", output.path("tool_calls").path(0).path("function").path("name").asText());

        // Trace attributes are on every span, not only the root.
        assertEquals("answer-message", attr(g, "langfuse.trace.name"));
        assertEquals("otto", attr(g, "langfuse.user.id"));
        assertEquals("conv_1", attr(g, "langfuse.session.id"));
        assertEquals("turn_1", attr(g, "langfuse.trace.metadata.turn_id"));
        assertEquals(List.of("assistant"), g.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags")));
        assertEquals("abc123", attr(g, "langfuse.release"));
        assertEquals("test", attr(g, "langfuse.environment"));
        assertEquals(Boolean.TRUE, span("answer-message").getAttributes().get(AttributeKey.booleanKey("aiops.ai_root")));
        assertNull(g.getAttributes().get(AttributeKey.booleanKey("aiops.ai_root")));
    }

    @Test
    void withoutContentCaptureNoPromptOrCompletionIsRecorded() {
        AiTracing t = tracing(false);
        TracedModelClient client = new TracedModelClient(model(ModelResult.Outcome.OK), t, "vllm");
        client.complete(new ModelRequest("policy", "evidence", JSON.createObjectNode(), 512));
        Span s = t.start("answer-message", AiTracing.Type.AGENT);
        t.contentInput(s, () -> "question");
        t.contentOutput(s, () -> "answer");
        t.input(s, Map.of("orderId", "ord_1"));
        s.end();

        SpanData g = span("generate-analysis");
        assertNull(attr(g, "langfuse.observation.input"));
        assertNull(attr(g, "langfuse.observation.output"));
        assertEquals(1200L, g.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        for (SpanData d : exported.getFinishedSpanItems()) {
            assertFalse(d.getAttributes().asMap().values().stream()
                .anyMatch(v -> String.valueOf(v).contains("evidence") || String.valueOf(v).contains("question")),
                "no content on " + d.getName());
        }
        assertEquals("{\"orderId\":\"ord_1\"}", attr(span("answer-message"), "langfuse.observation.input"),
            "service-written input is always recorded");
    }

    @Test
    void aFailedCallIsAnErrorObservationWithTheServiceDetail() {
        AiTracing t = tracing(true);
        new TracedModelClient(model(ModelResult.Outcome.UNAVAILABLE), t, "vllm")
            .complete(new ModelRequest("policy", "evidence", JSON.createObjectNode(), 512));
        SpanData g = span("generate-analysis");
        assertEquals("ERROR", attr(g, "langfuse.observation.level"));
        assertEquals("model endpoint HTTP 503", attr(g, "langfuse.observation.status_message"));
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, g.getStatus().getStatusCode());
        assertNull(attr(g, "langfuse.observation.output"));
    }

    @Test
    void langfuseReceivesOnlyAiSpansWithTheAiRootAsTraceRoot() {
        AiTracing t = tracing(true);
        Span request = provider.get("test").spanBuilder("POST /api/v1/ai/conversations/{id}/messages").startSpan();
        Span root;
        try (Scope ignored = request.makeCurrent()) {
            root = t.startTrace("answer-message", AiTracing.Type.AGENT, INFO);
            try (Scope inner = t.context(root, INFO).makeCurrent()) {
                t.start("get_order_timeline", AiTracing.Type.RETRIEVER).end();
                provider.get("test").spanBuilder("GET order-service").startSpan().end();
            }
            root.end();
        }
        request.end();

        List<SpanData> sent = new ArrayList<>();
        new LangfuseExport.AiSpans(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                sent.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }).export(exported.getFinishedSpanItems());

        assertEquals(List.of("get_order_timeline", "answer-message"), sent.stream().map(SpanData::getName).toList());
        SpanData sentRoot = sent.get(1);
        assertFalse(sentRoot.getParentSpanContext().isValid(), "the AI root is the Langfuse trace root");
        assertEquals(request.getSpanContext().getTraceId(), sentRoot.getTraceId(), "same trace ID as the collector");
        assertTrue(span("answer-message").getParentSpanContext().isValid(), "the collector still sees the request parent");
        assertEquals(root.getSpanContext().getSpanId(), sent.get(0).getParentSpanId());
    }
}
