package org.box1.aiops.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.box1.aiops.model.ChatMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** The LangChain4j model client against a local fake OpenAI-compatible (vLLM) server. */
class LangChain4jModelClientTest {
    static final ObjectMapper JSON = new ObjectMapper();
    /** Shaped like ChatTools' schemas: string properties with descriptions and a pattern, all required, closed. */
    static final JsonNode TOOL_SCHEMA = read("""
        {"type":"object","properties":{"orderId":{"type":"string","description":"Order id.",
        "pattern":"^ord_[a-z0-9]+$","maxLength":64}},"required":["orderId"],"additionalProperties":false}""");

    static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    final AtomicReference<String> upgradeHeader = new AtomicReference<>();
    final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    final AtomicReference<String> traceHeader = new AtomicReference<>();
    final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            traceHeader.set(exchange.getRequestHeaders().getFirst("traceparent"));
            lastRequest.set(JSON.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    LangChain4jModelClient client(Duration timeout) {
        return new LangChain4jModelClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "box1-coder",
            Duration.ofSeconds(1), timeout, JSON);
    }

    ModelRequest request() {
        return new ModelRequest("policy", "evidence", JSON.createObjectNode().put("type", "object"), 512);
    }

    static String completion(String content, String finish, String toolCalls) {
        String message = "{\"role\":\"assistant\",\"content\":" + (content == null ? "null" : JSON.valueToTree(content))
            + ",\"reasoning_content\":\"secret chain of thought\"" + (toolCalls == null ? "" : ",\"tool_calls\":"
            + toolCalls) + "}";
        return "{\"choices\":[{\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}],"
            + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300}}";
    }

    @Test
    void sendsGuidedJsonWithoutToolsAndReadsContentOnly() {
        reply.set(completion("{\"summary\":\"ok\"}", "stop", null));
        ModelResult r = client(Duration.ofSeconds(5)).complete(request());
        assertEquals(ModelResult.Outcome.OK, r.outcome());
        assertEquals("{\"summary\":\"ok\"}", r.content());
        assertEquals(1200, r.promptTokens());
        assertEquals(300, r.completionTokens());
        JsonNode sent = lastRequest.get();
        assertEquals("box1-coder", sent.path("model").asText());
        assertEquals("json_schema", sent.path("response_format").path("type").asText());
        assertFalse(sent.has("tools"), "no tools are ever offered to the model");
        assertFalse(sent.has("tool_choice"));
        assertEquals("system", sent.path("messages").path(0).path("role").asText());
        assertEquals(512, sent.path("max_tokens").asInt());
        assertEquals(0, sent.path("temperature").asInt(-1));
        JsonNode schema = sent.path("response_format").path("json_schema");
        assertEquals("incident_analysis", schema.path("name").asText());
        assertTrue(schema.path("strict").asBoolean(), "guided JSON is strict");
        assertEquals(request().responseSchema(), schema.path("schema"), "the schema is sent verbatim");
        assertEquals(false, sent.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        assertFalse(sent.path("stream").asBoolean(false));
    }

    @Test
    void sendsNoCredentialAndAddsTraceHeaders() {
        reply.set(completion("{}", "stop", null));
        new LangChain4jModelClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "box1-coder",
            Duration.ofSeconds(1), Duration.ofSeconds(5), JSON,
            b -> b.header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")).complete(request());
        assertEquals(null, authorizationHeader.get());
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", traceHeader.get());
    }

    @Test
    void failedCallsAreNotRetried() {
        reply.set("{}");
        status.set(503);
        client(Duration.ofSeconds(5)).complete(request());
        status.set(429);
        client(Duration.ofSeconds(5)).chat(chatRequest(true));
        assertEquals(2, requests.get());
    }

    @Test
    void oversizedResponseIsInvalid() {
        reply.set(completion("x".repeat(BoundedHttpClient.MAX_RESPONSE_BYTES + 1), "stop", null));
        ModelResult r = client(Duration.ofSeconds(5)).complete(request());
        assertEquals(ModelResult.Outcome.INVALID, r.outcome());
        assertTrue(r.detail().contains("too large"));
    }

    @Test
    void toolSchemaKeywordsThatCannotBeCarriedAreRefused() {
        reply.set(completion("unused", "stop", null));
        ChatRequest refs = new ChatRequest(java.util.List.of(ChatMessage.user("q")), java.util.List.of(
            new ToolSpec("t", "d", JSON.createObjectNode().put("type", "object").put("$ref", "#/x"))), 64);
        assertEquals(ModelResult.Outcome.INVALID, client(Duration.ofSeconds(5)).chat(refs).outcome());
        assertEquals(0, requests.get(), "nothing is sent with a loosened schema");
    }

    /** vLLM answered HTTP 400 to every request while the JDK client offered an h2c upgrade (live probe 2026-11-06). */
    @Test
    void neverOffersAnHttp2Upgrade() {
        reply.set(completion("{}", "stop", null));
        client(Duration.ofSeconds(5)).complete(request());
        assertEquals(null, upgradeHeader.get());
    }

    @Test
    void truncatedOutputIsInvalid() {
        reply.set(completion("{\"summary\":\"cut", "length", null));
        ModelResult r = client(Duration.ofSeconds(5)).complete(request());
        assertEquals(ModelResult.Outcome.INVALID, r.outcome());
        assertTrue(r.detail().contains("truncated"));
    }

    @Test
    void toolCallsAreRefused() {
        reply.set(completion(null, "tool_calls",
            "[{\"type\":\"function\",\"function\":{\"name\":\"delete_order\",\"arguments\":\"{}\"}}]"));
        ModelResult r = client(Duration.ofSeconds(5)).complete(request());
        assertEquals(ModelResult.Outcome.INVALID, r.outcome());
        assertTrue(r.detail().contains("tool"));
    }

    ChatRequest chatRequest(boolean withTools) {
        return new ChatRequest(java.util.List.of(ChatMessage.system("policy"), ChatMessage.user("question"),
            ChatMessage.assistant(null, java.util.List.of(new ToolCall("c0", "get_order_timeline", "{\"orderId\":\"ord_1\"}"))),
            ChatMessage.tool("c0", "{\"untrusted\":true}")),
            withTools ? java.util.List.of(new ToolSpec("get_order_timeline", "Read-only.", TOOL_SCHEMA))
                : java.util.List.of(), 256);
    }

    @Test
    void chatOffersToolsAndReturnsTheCallsForTheCallerToVet() {
        reply.set(completion(null, "tool_calls",
            "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_order_timeline\",\"arguments\":\"{\\\"orderId\\\":\\\"ord_2\\\"}\"}}]"));
        ChatResult r = client(Duration.ofSeconds(5)).chat(chatRequest(true));
        assertEquals(ModelResult.Outcome.OK, r.outcome());
        assertEquals(1, r.toolCalls().size());
        assertEquals("get_order_timeline", r.toolCalls().get(0).name());
        assertEquals("{\"orderId\":\"ord_2\"}", r.toolCalls().get(0).argumentsJson());
        JsonNode sent = lastRequest.get();
        assertEquals("get_order_timeline", sent.path("tools").path(0).path("function").path("name").asText());
        assertEquals("auto", sent.path("tool_choice").asText());
        JsonNode parameters = sent.path("tools").path(0).path("function").path("parameters");
        assertEquals(TOOL_SCHEMA.path("properties"), parameters.path("properties"), "property schemas are verbatim");
        assertEquals(TOOL_SCHEMA.path("required"), parameters.path("required"));
        assertTrue(sent.path("tools").path(0).path("function").path("strict").isMissingNode(),
            "strict tools would make every parameter required");
        assertTrue(sent.path("response_format").isMissingNode(), "chat answers in text");
        assertEquals("tool", sent.path("messages").path(3).path("role").asText());
        assertEquals("c0", sent.path("messages").path(3).path("tool_call_id").asText());
        assertEquals("c0", sent.path("messages").path(2).path("tool_calls").path(0).path("id").asText());
        assertEquals(false, sent.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
    }

    @Test
    void chatTextAnswersAreReturnedAndReasoningIsDropped() {
        reply.set(completion("The order is waiting on the ERP.", "stop", null));
        ChatResult r = client(Duration.ofSeconds(5)).chat(chatRequest(true));
        assertEquals("The order is waiting on the ERP.", r.content());
        assertTrue(r.toolCalls().isEmpty());
        assertFalse(String.valueOf(r).contains("chain of thought"));
    }

    @Test
    void chatRefusesToolCallsWhenNoneWereOfferedAndTruncation() {
        reply.set(completion(null, "tool_calls",
            "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"delete_order\",\"arguments\":\"{}\"}}]"));
        ChatResult r = client(Duration.ofSeconds(5)).chat(chatRequest(false));
        assertEquals(ModelResult.Outcome.INVALID, r.outcome());
        assertTrue(lastRequest.get().path("tools").isMissingNode());
        reply.set(completion("cut off", "length", null));
        assertEquals(ModelResult.Outcome.INVALID, client(Duration.ofSeconds(5)).chat(chatRequest(true)).outcome());
        status.set(503);
        reply.set("{}");
        assertEquals(ModelResult.Outcome.UNAVAILABLE, client(Duration.ofSeconds(5)).chat(chatRequest(true)).outcome());
    }

    @Test
    void emptyContentAndGarbageAreInvalid() {
        reply.set(completion("", "stop", null));
        assertEquals(ModelResult.Outcome.INVALID, client(Duration.ofSeconds(5)).complete(request()).outcome());
        reply.set("<html>proxy error</html>");
        assertEquals(ModelResult.Outcome.INVALID, client(Duration.ofSeconds(5)).complete(request()).outcome());
    }

    @Test
    void serverErrorsAndOverloadAreUnavailable() {
        reply.set("{}");
        for (int code : new int[] {503, 500, 429, 404}) {
            status.set(code);
            assertEquals(ModelResult.Outcome.UNAVAILABLE, client(Duration.ofSeconds(5)).complete(request()).outcome(),
                "HTTP " + code);
        }
        status.set(400);
        assertEquals(ModelResult.Outcome.INVALID, client(Duration.ofSeconds(5)).complete(request()).outcome());
    }

    @Test
    void slowModelTimesOut() {
        reply.set(completion("{}", "stop", null));
        delayMs.set(1500L);
        assertEquals(ModelResult.Outcome.TIMEOUT, client(Duration.ofMillis(300)).complete(request()).outcome());
    }

    @Test
    void unreachableModelIsUnavailable() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        LangChain4jModelClient down = new LangChain4jModelClient("http://127.0.0.1:" + port + "/v1", "box1-coder",
            Duration.ofMillis(500), Duration.ofSeconds(1), JSON);
        assertEquals(ModelResult.Outcome.UNAVAILABLE, down.complete(request()).outcome());
    }
}
