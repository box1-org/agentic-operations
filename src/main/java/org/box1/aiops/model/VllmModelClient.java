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
 * OpenAI-compatible {@code /chat/completions} against vLLM with guided JSON output. No tools are offered, and a
 * completion that carries tool calls, is cut off, or is empty is reported as INVALID rather than used. Reasoning
 * output is ignored and never returned or logged. One attempt per analysis: POSTs are not retried.
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
        byte[] body;
        try {
            body = json.writeValueAsBytes(body(request));
        } catch (IOException e) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "request could not be encoded");
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
            return ModelResult.failure(ModelResult.Outcome.TIMEOUT, "model did not answer within " + timeout);
        } catch (BoundedHttp.Unreachable e) {
            return ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint unreachable");
        }
        int status = response.status();
        if (status == 408 || status == 429 || status >= 500 || status == 404) {
            return ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint HTTP " + status);
        }
        if (status != 200) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "model rejected the request (HTTP " + status + ")");
        }
        if (response.tooLarge()) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "model response too large");
        }
        return parse(response.body());
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
