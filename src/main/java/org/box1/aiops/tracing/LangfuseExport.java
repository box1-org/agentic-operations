package org.box1.aiops.tracing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.box1.aiops.AiopsConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.logging.Log;

/**
 * Sends the AI spans to Langfuse over OTLP/HTTP (https://langfuse.com/integrations/native/opentelemetry), next to the
 * collector export, which still receives every span. Quarkus registers this {@link SpanProcessor} bean with the SDK.
 * Only spans {@link AiTracing} shaped are sent: HTTP, Kafka and upstream spans stay out of Langfuse. The root of each
 * AI trace runs under the submitting request in the collector's trace; in Langfuse it is sent as the trace root (same
 * trace ID), so the trace's input and output are the analysis's or the message's. Without a key pair this processor
 * does nothing.
 */
@ApplicationScoped
public class LangfuseExport {
    static final String INGESTION_PATH = "/api/public/otel/v1/traces";
    static final AttributeKey<String> TYPE = AttributeKey.stringKey(AiTracing.TYPE);
    static final AttributeKey<Boolean> ROOT = AttributeKey.booleanKey(AiTracing.ROOT);

    @Produces
    @Singleton
    SpanProcessor langfuse(AiopsConfig config) {
        AiopsConfig.Langfuse lf = config.langfuse();
        Optional<String> publicKey = lf.publicKey();
        Optional<String> secretKey = lf.secretKey();
        if (publicKey.isEmpty() || secretKey.isEmpty()) {
            Log.info("Langfuse export off: LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY are not both set");
            return SpanProcessor.composite();
        }
        String auth = Base64.getEncoder().encodeToString((publicKey.get() + ":" + secretKey.get())
            .getBytes(StandardCharsets.UTF_8));
        String endpoint = lf.baseUrl().replaceAll("/+$", "") + INGESTION_PATH;
        SpanExporter otlp = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(lf.timeout())
            .addHeader("Authorization", "Basic " + auth)
            // Real-time ingestion on the Langfuse v4 data model.
            .addHeader("x-langfuse-ingestion-version", "4").build();
        Log.infof("Langfuse export on: AI spans to %s", endpoint);
        return BatchSpanProcessor.builder(new AiSpans(otlp)).build();
    }

    /** Passes on AI spans only, with each AI trace root detached from the request span it ran under. */
    static final class AiSpans implements SpanExporter {
        private final SpanExporter delegate;

        AiSpans(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> ai = new ArrayList<>();
            for (SpanData s : spans) {
                if (s.getAttributes().get(TYPE) == null) {
                    continue;
                }
                ai.add(Boolean.TRUE.equals(s.getAttributes().get(ROOT)) ? new Root(s) : s);
            }
            return ai.isEmpty() ? CompletableResultCode.ofSuccess() : delegate.export(ai);
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    /** The span as a trace root: same trace and span IDs, no parent. */
    static final class Root extends DelegatingSpanData {
        Root(SpanData delegate) {
            super(delegate);
        }

        @Override
        public SpanContext getParentSpanContext() {
            return SpanContext.getInvalid();
        }
    }
}
