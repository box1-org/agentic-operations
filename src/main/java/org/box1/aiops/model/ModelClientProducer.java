package org.box1.aiops.model;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.http.HttpRequest;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.tracing.AiTracing;
import org.box1.aiops.tracing.TracedModelClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;

@ApplicationScoped
public class ModelClientProducer {

    @Produces
    @ApplicationScoped
    ModelClient modelClient(AiopsConfig config, ObjectMapper json, OpenTelemetry otel, AiTracing tracing) {
        AiopsConfig.Model m = config.model();
        ModelClient client = new LangChain4jModelClient(m.baseUrl(), m.id(), m.connectTimeout(), m.timeout(), json,
            b -> otel.getPropagators().getTextMapPropagator().inject(Context.current(), b, HttpRequest.Builder::header));
        return new TracedModelClient(client, tracing, "vllm");
    }
}
