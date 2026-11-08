package org.box1.aiops.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.box1.aiops.BoundedHttp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible {@code /chat/completions} against vLLM. {@link #complete} (analyses) uses guided JSON output and
 * offers no tools; a completion that carries tool calls, is cut off, or is empty is reported as INVALID rather than
 * used. {@link #chat} (the Assistant, D-016) offers the given tools and returns any tool calls for the caller to vet.
 * Reasoning output is ignored and never returned or logged. One attempt per call: POSTs are not retried.
 */
public class VllmModelClient implements ModelClient {
    static final int MAX_RESPONSE_BYTES = 262_144;

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    private final java.util.function.Consumer<HttpRequest.Builder> decorate;

    public VllmModelClient(String baseUrl, String model, Duration connectTimeout, Duration timeout, ObjectMapper json) {
        this(baseUrl, model, connectTimeout, timeout, json, b -> {
        });
    }

    /** {@code decorate} adds trace propagation headers; it never adds credentials or content. */
    public VllmModelClient(String baseUrl, String model, Duration connectTimeout, Duration timeout, ObjectMapper json,
            java.util.function.Consumer<HttpRequest.Builder> decorate) {
        this.decorate = decorate;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.model = model;
        this.timeout = timeout;
        this.json = json;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        Sent sent = send(body(request));
        return sent.failure() != null ? sent.failure() : parse(sent.body());
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Sent sent = send(chatBody(request));
        if (sent.failure() != null) {
            return ChatResult.failure(sent.failure().outcome(), sent.failure().detail());
        }
        return parseChat(sent.body(), !request.tools().isEmpty());
    }

    /** The raw response body, or the failure that stands for it. */
    private record Sent(byte[] body, ModelResult failure) {
    }

    private Sent send(ObjectNode payload) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(payload);
        } catch (IOException e) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.INVALID, "request could not be encoded"));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        decorate.accept(builder);
        HttpRequest http = builder.build();
        BoundedHttp.Result response;
        try {
            response = BoundedHttp.send(this.http, http, MAX_RESPONSE_BYTES, timeout);
        } catch (BoundedHttp.Timeout e) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.TIMEOUT, "model did not answer within " + timeout));
        } catch (BoundedHttp.Unreachable e) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint unreachable"));
        }
        int status = response.status();
        if (status == 408 || status == 429 || status >= 500 || status == 404) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint HTTP " + status));
        }
        if (status != 200) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.INVALID,
                "model rejected the request (HTTP " + status + ")"));
        }
        if (response.tooLarge()) {
            return new Sent(null, ModelResult.failure(ModelResult.Outcome.INVALID, "model response too large"));
        }
        return new Sent(response.body(), null);
    }

    ObjectNode body(ModelRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        messages.addObject().put("role", "user").put("content", request.user());
        root.put("temperature", 0);
        root.put("max_tokens", request.maxTokens());
        ObjectNode format = root.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode schema = format.putObject("json_schema");
        schema.put("name", "incident_analysis");
        schema.put("strict", true);
        schema.set("schema", request.responseSchema());
        root.putObject("chat_template_kwargs").put("enable_thinking", false);
        return root;
    }

    ObjectNode chatBody(ChatRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode out = messages.addObject();
            out.put("role", m.role());
            if (m.content() != null) {
                out.put("content", m.content());
            } else {
                out.putNull("content");
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = out.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    call.putObject("function").put("name", c.name()).put("arguments", c.argumentsJson());
                }
            }
            if (m.toolCallId() != null) {
                out.put("tool_call_id", m.toolCallId());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.parameters());
            }
            root.put("tool_choice", "auto");
        }
        root.put("temperature", 0);
        root.put("max_tokens", request.maxTokens());
        root.putObject("chat_template_kwargs").put("enable_thinking", false);
        return root;
    }

    /** Tool calls are returned only when tools were offered; otherwise they are refused as for analyses. */
    ChatResult parseChat(byte[] raw, boolean toolsOffered) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            return ChatResult.failure(ModelResult.Outcome.INVALID, "model response is not JSON");
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            return ChatResult.failure(ModelResult.Outcome.INVALID, "model response has no choices");
        }
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asText(null);
        Integer prompt = root.path("usage").has("prompt_tokens") ? root.path("usage").path("prompt_tokens").asInt()
            : null;
        Integer completion = root.path("usage").has("completion_tokens")
            ? root.path("usage").path("completion_tokens").asInt() : null;
        if ("length".equals(finish)) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, java.util.List.of(), finish, prompt, completion,
                "model output was truncated at the token limit");
        }
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode c : toolCalls) {
                JsonNode fn = c.path("function");
                JsonNode args = fn.path("arguments");
                calls.add(new ToolCall(c.path("id").asText(""), fn.path("name").asText(""),
                    args.isTextual() ? args.textValue() : args.toString()));
            }
        }
        if (!calls.isEmpty() && !toolsOffered) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, java.util.List.of(), finish, prompt, completion,
                "model attempted a tool call; no tools were offered");
        }
        String content = message.path("content").isTextual() ? message.path("content").textValue() : null;
        if (calls.isEmpty() && (content == null || content.isBlank())) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, java.util.List.of(), finish, prompt, completion,
                "model returned no content");
        }
        return new ChatResult(ModelResult.Outcome.OK, content, java.util.List.copyOf(calls), finish, prompt,
            completion, null);
    }

    ModelResult parse(byte[] raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "model response is not JSON");
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "model response has no choices");
        }
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asText(null);
        Integer prompt = root.path("usage").has("prompt_tokens") ? root.path("usage").path("prompt_tokens").asInt()
            : null;
        Integer completion = root.path("usage").has("completion_tokens")
            ? root.path("usage").path("completion_tokens").asInt() : null;
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, finish, prompt, completion,
                "model attempted a tool call; tools are not permitted");
        }
        if ("length".equals(finish)) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, finish, prompt, completion,
                "model output was truncated at the token limit");
        }
        String content = message.path("content").isTextual() ? message.path("content").textValue() : null;
        if (content == null || content.isBlank()) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, finish, prompt, completion,
                "model returned no content");
        }
        return new ModelResult(ModelResult.Outcome.OK, content, finish, prompt, completion, null);
    }
}
