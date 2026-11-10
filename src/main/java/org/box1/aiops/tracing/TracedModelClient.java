package org.box1.aiops.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * Records each model call as a Langfuse {@code generation} with the OpenTelemetry GenAI attributes: model, request
 * parameters, token usage and finish reason. With content capture on, the input is the exact message list sent
 * (system, user, assistant with {@code tool_calls}, tool results) in the OpenAI shape Langfuse renders as a
 * conversation, and the output is the assistant message. A failed call is an ERROR observation with the
 * service-written detail. The wrapped client is unchanged.
 */
public class TracedModelClient implements ModelClient {
    static final String ANALYSIS = "generate-analysis";
    static final String REPLY = "generate-reply";

    private final ModelClient delegate;
    private final AiTracing tracing;
    private final String provider;

    public TracedModelClient(ModelClient delegate, AiTracing tracing, String provider) {
        this.delegate = delegate;
        this.tracing = tracing;
        this.provider = provider;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        Span span = generation(ANALYSIS, request.maxTokens());
        tracing.metadata(span, "response_format", "json_schema:incident_analysis");
        tracing.contentInput(span, () -> List.of(message("system", request.system()),
            message("user", request.user())));
        ModelResult r;
        try (Scope ignored = span.makeCurrent()) {
            r = delegate.complete(request);
        } catch (RuntimeException e) {
            tracing.level(span, AiTracing.Level.ERROR, "model call failed");
            span.end();
            throw e;
        }
        finish(span, r.outcome(), r.finishReason(), r.promptTokens(), r.completionTokens(), r.detail());
        if (r.outcome() == ModelResult.Outcome.OK) {
            tracing.contentOutput(span, () -> message("assistant", r.content()));
        }
        span.end();
        return r;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Span span = generation(REPLY, request.maxTokens());
        tracing.metadata(span, "tools_offered", String.join(",", request.tools().stream().map(ToolSpec::name).toList()));
        tracing.contentInput(span, () -> request.messages().stream().map(TracedModelClient::message).toList());
        ChatResult r;
        try (Scope ignored = span.makeCurrent()) {
            r = delegate.chat(request);
        } catch (RuntimeException e) {
            tracing.level(span, AiTracing.Level.ERROR, "model call failed");
            span.end();
            throw e;
        }
        finish(span, r.outcome(), r.finishReason(), r.promptTokens(), r.completionTokens(), r.detail());
        if (r.outcome() == ModelResult.Outcome.OK) {
            tracing.contentOutput(span, () -> message(ChatMessage.assistant(r.content(), r.toolCalls())));
            tracing.metadata(span, "tool_calls", r.toolCalls().size());
        }
        span.end();
        return r;
    }

    private Span generation(String name, int maxTokens) {
        Span span = tracing.start(name, AiTracing.Type.GENERATION);
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.provider.name", provider);
        span.setAttribute("gen_ai.request.model", delegate.modelId());
        span.setAttribute("gen_ai.request.temperature", 0.0);
        span.setAttribute("gen_ai.request.max_tokens", (long) maxTokens);
        return span;
    }

    private void finish(Span span, ModelResult.Outcome outcome, String finishReason, Integer promptTokens,
            Integer completionTokens, String detail) {
        span.setAttribute("gen_ai.response.model", delegate.modelId());
        if (finishReason != null) {
            span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), List.of(finishReason));
        }
        if (promptTokens != null) {
            span.setAttribute("gen_ai.usage.input_tokens", (long) promptTokens);
        }
        if (completionTokens != null) {
            span.setAttribute("gen_ai.usage.output_tokens", (long) completionTokens);
        }
        tracing.metadata(span, "outcome", outcome.name());
        if (outcome != ModelResult.Outcome.OK) {
            tracing.level(span, AiTracing.Level.ERROR, detail);
        }
    }

    static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** One message in the OpenAI chat shape: tool call arguments stay a JSON-encoded string. */
    static Map<String, Object> message(ChatMessage in) {
        Map<String, Object> m = message(in.role(), in.content());
        if (!in.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall c : in.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name());
                fn.put("arguments", c.argumentsJson());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", c.id());
                call.put("type", "function");
                call.put("function", fn);
                calls.add(call);
            }
            m.put("tool_calls", calls);
        }
        if (in.toolCallId() != null) {
            m.put("tool_call_id", in.toolCallId());
        }
        return m;
    }
}
