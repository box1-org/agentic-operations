package org.box1.aiops.model;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.http.HttpRequest;

import org.box1.aiops.AiopsConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;

@ApplicationScoped
public class ModelClientProducer {

    @Produces
    @ApplicationScoped
    ModelClient modelClient(AiopsConfig config, ObjectMapper json, OpenTelemetry otel) {
        AiopsConfig.Model m = config.model();
        return new LangChain4jModelClient(m.baseUrl(), m.id(), m.connectTimeout(), m.timeout(), json,
            b -> otel.getPropagators().getTextMapPropagator().inject(Context.current(), b, HttpRequest.Builder::header));
    }
}
