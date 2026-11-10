package org.box1.aiops.model;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

/**
 * The model through LangChain4j's OpenAI-compatible chat model, against vLLM. {@link #complete} (analyses) uses strict
 * guided JSON output and offers no tools; a completion that carries tool calls, is cut off, or is empty is reported as
 * INVALID rather than used. {@link #chat} (the Assistant, D-016) offers the given tools and returns any tool calls for
 * the caller to vet: LangChain4j never runs a tool here. Reasoning output is neither requested nor returned, nothing is
 * logged, and a call is one attempt: POSTs are not retried. The transport is {@link BoundedHttpClient}.
 */
public class LangChain4jModelClient implements ModelClient {
    /** vLLM chat-template switch: the model answers without a reasoning preamble. */
    private static final Map<String, Object> NO_THINKING = Map.of("chat_template_kwargs",
        Map.of("enable_thinking", false));

    /** Top-level keywords {@link #objectSchema} carries over; any other one is refused rather than dropped. */
    private static final java.util.Set<String> OBJECT_KEYWORDS = java.util.Set.of("type", "description", "properties",
        "required", "additionalProperties");

    private final ChatModel chatModel;
    private final ObjectMapper json;
    private final String model;

    public LangChain4jModelClient(String baseUrl, String model, Duration connectTimeout, Duration timeout,
            ObjectMapper json) {
        this(baseUrl, model, connectTimeout, timeout, json, b -> {
        });
    }

    /** {@code decorate} adds trace propagation headers; it never adds credentials or content. */
    public LangChain4jModelClient(String baseUrl, String model, Duration connectTimeout, Duration timeout,
            ObjectMapper json, Consumer<HttpRequest.Builder> decorate) {
        this.model = model;
        this.json = json;
        this.chatModel = OpenAiChatModel.builder()
            .httpClientBuilder(new BoundedHttpClient.Builder(connectTimeout, timeout, decorate))
            .baseUrl(baseUrl)
            .modelName(model)
            .timeout(timeout)
            .maxRetries(0)
            .strictJsonSchema(true)
            .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
            .returnThinking(false)
            .logRequests(false)
            .logResponses(false)
            .build();
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        ResponseFormat format;
        try {
            format = ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("incident_analysis")
                    .rootElement(JsonRawSchema.from(json.writeValueAsString(request.responseSchema()))).build())
                .build();
        } catch (Exception e) {
            return ModelResult.failure(ModelResult.Outcome.INVALID, "request could not be encoded");
        }
        Answer a = call(List.of(SystemMessage.from(request.system()), UserMessage.from(request.user())),
            parameters(request.maxTokens()).responseFormat(format).build());
        if (a.failure() != null) {
            return ModelResult.failure(a.failure().outcome(), a.failure().detail());
        }
        if (a.ai().hasToolExecutionRequests()) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, a.finish(), a.prompt(), a.completion(),
                "model attempted a tool call; tools are not permitted");
        }
        if (a.response().finishReason() == FinishReason.LENGTH) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, a.finish(), a.prompt(), a.completion(),
                "model output was truncated at the token limit");
        }
        String content = a.ai().text();
        if (content == null || content.isBlank()) {
            return new ModelResult(ModelResult.Outcome.INVALID, null, a.finish(), a.prompt(), a.completion(),
                "model returned no content");
        }
        return new ModelResult(ModelResult.Outcome.OK, content, a.finish(), a.prompt(), a.completion(), null);
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        boolean toolsOffered = !request.tools().isEmpty();
        List<dev.langchain4j.data.message.ChatMessage> messages;
        OpenAiChatRequestParameters.Builder params = parameters(request.maxTokens());
        try {
            messages = messages(request.messages());
            if (toolsOffered) {
                List<ToolSpecification> tools = new ArrayList<>();
                for (ToolSpec t : request.tools()) {
                    tools.add(ToolSpecification.builder().name(t.name()).description(t.description())
                        .parameters(objectSchema(t.parameters())).build());
                }
                params.toolSpecifications(tools).toolChoice(ToolChoice.AUTO);
            }
        } catch (Exception e) {
            return ChatResult.failure(ModelResult.Outcome.INVALID, "request could not be encoded");
        }
        Answer a = call(messages, params.build());
        if (a.failure() != null) {
            return ChatResult.failure(a.failure().outcome(), a.failure().detail());
        }
        if (a.response().finishReason() == FinishReason.LENGTH) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, List.of(), a.finish(), a.prompt(),
                a.completion(), "model output was truncated at the token limit");
        }
        List<ToolCall> calls = new ArrayList<>();
        if (a.ai().hasToolExecutionRequests()) {
            for (ToolExecutionRequest r : a.ai().toolExecutionRequests()) {
                calls.add(new ToolCall(r.id() == null ? "" : r.id(), r.name() == null ? "" : r.name(),
                    r.arguments() == null ? "" : r.arguments()));
            }
        }
        if (!calls.isEmpty() && !toolsOffered) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, List.of(), a.finish(), a.prompt(),
                a.completion(), "model attempted a tool call; no tools were offered");
        }
        String content = a.ai().text();
        if (calls.isEmpty() && (content == null || content.isBlank())) {
            return new ChatResult(ModelResult.Outcome.INVALID, null, List.of(), a.finish(), a.prompt(),
                a.completion(), "model returned no content");
        }
        return new ChatResult(ModelResult.Outcome.OK, content, List.copyOf(calls), a.finish(), a.prompt(),
            a.completion(), null);
    }

    private OpenAiChatRequestParameters.Builder parameters(int maxTokens) {
        return OpenAiChatRequestParameters.builder().modelName(model).temperature(0.0).maxOutputTokens(maxTokens)
            .customParameters(NO_THINKING);
    }

    /** A response, or the failure that stands for it. */
    private record Answer(ChatResponse response, ModelResult failure) {
        AiMessage ai() {
            return response.aiMessage();
        }

        String finish() {
            return finishReason(response.finishReason());
        }

        Integer prompt() {
            TokenUsage u = response.tokenUsage();
            return u == null ? null : u.inputTokenCount();
        }

        Integer completion() {
            TokenUsage u = response.tokenUsage();
            return u == null ? null : u.outputTokenCount();
        }
    }

    private Answer call(List<dev.langchain4j.data.message.ChatMessage> messages, OpenAiChatRequestParameters params) {
        ChatResponse response;
        try {
            response = chatModel.chat(dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages)
                .parameters(params).build());
        } catch (RuntimeException e) {
            return new Answer(null, failure(e));
        }
        if (response == null || response.aiMessage() == null) {
            return new Answer(null, ModelResult.failure(ModelResult.Outcome.INVALID, "model response has no choices"));
        }
        return new Answer(response, null);
    }

    /** Maps a LangChain4j failure to a fixed, service-written outcome; exception text is never passed on. */
    static ModelResult failure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BoundedHttpClient.Failure f) {
                return ModelResult.failure(f.outcome(), f.getMessage());
            }
            if (t instanceof HttpException h) {
                int status = h.statusCode();
                if (status == 408 || status == 429 || status >= 500 || status == 404) {
                    return ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint HTTP " + status);
                }
                return ModelResult.failure(ModelResult.Outcome.INVALID,
                    "model rejected the request (HTTP " + status + ")");
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return ModelResult.failure(ModelResult.Outcome.INVALID, "model response could not be read");
    }

    /** The OpenAI wire value, as vLLM sent it, for the analysis record and logs. */
    static String finishReason(FinishReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_EXECUTION -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case OTHER -> "other";
        };
    }

    /** Service messages to LangChain4j messages; a tool reply carries the name of the call it answers. */
    static List<dev.langchain4j.data.message.ChatMessage> messages(List<ChatMessage> in) {
        Map<String, String> toolNames = new HashMap<>();
        List<dev.langchain4j.data.message.ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : in) {
            switch (m.role()) {
                case "system" -> out.add(SystemMessage.from(m.content()));
                case "user" -> out.add(UserMessage.from(m.content()));
                case "assistant" -> {
                    List<ToolExecutionRequest> requests = new ArrayList<>();
                    for (ToolCall c : m.toolCalls()) {
                        toolNames.put(c.id(), c.name());
                        requests.add(ToolExecutionRequest.builder().id(c.id()).name(c.name())
                            .arguments(c.argumentsJson()).build());
                    }
                    out.add(AiMessage.builder().text(m.content()).toolExecutionRequests(requests).build());
                }
                case "tool" -> out.add(ToolExecutionResultMessage.from(m.toolCallId(),
                    toolNames.getOrDefault(m.toolCallId(), ""), m.content()));
                default -> throw new IllegalArgumentException("unknown role");
            }
        }
        return out;
    }

    /**
     * A tool's JSON-schema parameters as LangChain4j's object schema. Each property keeps its schema verbatim; any
     * other top-level keyword is refused. LangChain4j sends {@code additionalProperties} only for strict tools, and
     * strict tools would make every parameter required (search_logs takes orderId or traceId), so the model is not
     * told the object is closed. That is a hint only: ChatTools reads the keys it knows and validates each value.
     */
    static JsonObjectSchema objectSchema(JsonNode schema) {
        if (!"object".equals(schema.path("type").asText())) {
            throw new IllegalArgumentException("tool parameters must be an object schema");
        }
        schema.fieldNames().forEachRemaining(k -> {
            if (!OBJECT_KEYWORDS.contains(k)) {
                throw new IllegalArgumentException("unsupported tool schema keyword");
            }
        });
        JsonObjectSchema.Builder b = JsonObjectSchema.builder();
        if (schema.path("description").isTextual()) {
            b.description(schema.path("description").textValue());
        }
        Map<String, JsonSchemaElement> properties = new java.util.LinkedHashMap<>();
        schema.path("properties").properties()
            .forEach(p -> properties.put(p.getKey(), JsonRawSchema.from(p.getValue().toString())));
        b.addProperties(properties);
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        if (!required.isEmpty()) {
            b.required(required);
        }
        if (schema.path("additionalProperties").isBoolean()) {
            b.additionalProperties(schema.path("additionalProperties").booleanValue());
        }
        return b.build();
    }
}
