package org.box1.aiops.tracing;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

/**
 * Spans for the AI work, shaped for Langfuse as well as Tempo (https://langfuse.com/docs/observability/best-practices).
 * An analysis is one trace rooted at {@code analyze-incident}; an Assistant message is one trace rooted at
 * {@code answer-message}, grouped into a session per conversation. Every span gets a Langfuse observation type, and
 * the trace attributes (name, user, session, tags, release, metadata) are copied onto every span of the trace from the
 * OpenTelemetry {@link Context}, never through baggage, so nothing leaves the service in request headers.
 *
 * <p>Input and output (prompts, completions, evidence, questions and answers) are recorded only when
 * {@code aiops.tracing.capture-content} is true; by default that is when a Langfuse key is configured. Without it, spans
 * carry IDs, types, statuses, model, token counts and service-written details only.
 */
@ApplicationScoped
public class AiTracing {
    static final String TYPE = "langfuse.observation.type";
    static final String INPUT = "langfuse.observation.input";
    static final String OUTPUT = "langfuse.observation.output";
    static final String LEVEL = "langfuse.observation.level";
    static final String STATUS_MESSAGE = "langfuse.observation.status_message";
    static final String OBSERVATION_METADATA = "langfuse.observation.metadata.";
    static final String TRACE_NAME = "langfuse.trace.name";
    static final String TRACE_TAGS = "langfuse.trace.tags";
    static final String TRACE_METADATA = "langfuse.trace.metadata.";
    static final String USER_ID = "langfuse.user.id";
    static final String SESSION_ID = "langfuse.session.id";
    static final String RELEASE = "langfuse.release";
    static final String ENVIRONMENT = "langfuse.environment";
    /** Marks the root span of an AI trace, which {@link LangfuseExport} sends as the Langfuse trace root. */
    static final String ROOT = "aiops.ai_root";

    /** Observation types used here (https://langfuse.com/docs/observability/features/observation-types). */
    public enum Type {
        CHAIN("chain"), AGENT("agent"), GENERATION("generation"), RETRIEVER("retriever"), EVALUATOR("evaluator");

        final String value;

        Type(String value) {
            this.value = value;
        }
    }

    /** Langfuse observation levels. */
    public enum Level {
        WARNING, ERROR
    }

    /** Trace-level attributes, copied onto every span of the trace. {@code sessionId} may be null. */
    public record TraceInfo(String name, String userId, String sessionId, List<String> tags,
            Map<String, String> metadata) {
    }

    private static final ContextKey<TraceInfo> TRACE = ContextKey.named("box1-ai-trace");

    private final Tracer tracer;
    private final ObjectMapper json;
    private final boolean captureContent;
    private final String release;
    private final String environment;

    @Inject
    public AiTracing(Tracer tracer, ObjectMapper json, AiopsConfig config) {
        this(tracer, json, config.tracing().captureContent().orElse(config.langfuse().publicKey().isPresent()),
            org.box1.InfoResource.gitRef(), config.langfuse().environment());
    }

    public AiTracing(Tracer tracer, ObjectMapper json, boolean captureContent, String release, String environment) {
        this.tracer = tracer;
        this.json = json;
        this.captureContent = captureContent;
        this.release = release;
        this.environment = environment;
    }

    public boolean capturesContent() {
        return captureContent;
    }

    /**
     * Starts the root span of an AI trace under the current context (the submitting request, so Tempo keeps one
     * trace per request). The returned context carries {@code info} for {@link #start}.
     */
    public Span startTrace(String name, Type type, TraceInfo info) {
        Span span = tracer.spanBuilder(name).startSpan();
        span.setAttribute(TYPE, type.value);
        span.setAttribute(ROOT, true);
        applyTrace(span, info);
        return span;
    }

    /** The context to make current for the root span: it carries the trace attributes for child spans. */
    public Context context(Span root, TraceInfo info) {
        return Context.current().with(root).with(TRACE, info);
    }

    /** Starts a child span of the current span, with the current trace's attributes. */
    public Span start(String name, Type type) {
        Span span = tracer.spanBuilder(name).startSpan();
        span.setAttribute(TYPE, type.value);
        TraceInfo info = Context.current().get(TRACE);
        if (info != null) {
            applyTrace(span, info);
        }
        return span;
    }

    private void applyTrace(Span span, TraceInfo info) {
        span.setAttribute(TRACE_NAME, info.name());
        if (info.userId() != null) {
            span.setAttribute(USER_ID, info.userId());
        }
        if (info.sessionId() != null) {
            span.setAttribute(SESSION_ID, info.sessionId());
        }
        if (!info.tags().isEmpty()) {
            span.setAttribute(AttributeKey.stringArrayKey(TRACE_TAGS), info.tags());
        }
        info.metadata().forEach((k, v) -> span.setAttribute(TRACE_METADATA + k, v));
        if (release != null && !"unknown".equals(release)) {
            span.setAttribute(RELEASE, release);
        }
        span.setAttribute(ENVIRONMENT, environment);
    }

    /** Input that is always safe to record: IDs and service-written values only. */
    public void input(Span span, Object value) {
        span.setAttribute(INPUT, write(value));
    }

    /** Output that is always safe to record: IDs and service-written values only. */
    public void output(Span span, Object value) {
        span.setAttribute(OUTPUT, write(value));
    }

    /** Content input (prompts, evidence, questions, tool arguments): recorded only when content capture is on. */
    public void contentInput(Span span, Supplier<Object> value) {
        if (captureContent) {
            span.setAttribute(INPUT, write(value.get()));
        }
    }

    /** Content output (completions, answers, tool results): recorded only when content capture is on. */
    public void contentOutput(Span span, Supplier<Object> value) {
        if (captureContent) {
            span.setAttribute(OUTPUT, write(value.get()));
        }
    }

    /** Filterable observation metadata. Values must be IDs or service-written text. */
    public void metadata(Span span, String key, Object value) {
        if (value != null) {
            span.setAttribute(OBSERVATION_METADATA + key, String.valueOf(value));
        }
    }

    /** Marks the observation; {@code message} is service-written text, never model or upstream output. */
    public void level(Span span, Level level, String message) {
        span.setAttribute(LEVEL, level.name());
        if (message != null) {
            span.setAttribute(STATUS_MESSAGE, message);
        }
        if (level == Level.ERROR) {
            span.setStatus(StatusCode.ERROR, message == null ? "" : message);
        }
    }

    String write(Object value) {
        if (value instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"(not serializable)\"";
        }
    }
}
