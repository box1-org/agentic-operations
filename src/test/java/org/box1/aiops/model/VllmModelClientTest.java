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
import java.util.concurrent.atomic.AtomicReference;

import org.box1.aiops.model.ChatMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** The vLLM client against a local fake OpenAI-compatible server. */
class VllmModelClientTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    final AtomicReference<String> upgradeHeader = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
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

    VllmModelClient client(Duration timeout) {
        return new VllmModelClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "box1-coder",
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
            withTools ? java.util.List.of(new ToolSpec("get_order_timeline", "Read-only.",
                JSON.createObjectNode().put("type", "object"))) : java.util.List.of(), 256);
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
        VllmModelClient down = new VllmModelClient("http://127.0.0.1:" + port + "/v1", "box1-coder",
            Duration.ofMillis(500), Duration.ofSeconds(1), JSON);
        assertEquals(ModelResult.Outcome.UNAVAILABLE, down.complete(request()).outcome());
    }
}
