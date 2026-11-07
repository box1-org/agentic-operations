package org.box1.aiops.model;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.box1.aiops.AiopsConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class ModelClientProducer {

    @Produces
    @ApplicationScoped
    ModelClient modelClient(AiopsConfig config, ObjectMapper json) {
        AiopsConfig.Model m = config.model();
        return new VllmModelClient(m.baseUrl(), m.id(), m.connectTimeout(), m.timeout(), json);
    }
}
