package org.box1.aiops.window;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.box1.aiops.AiopsConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@ApplicationScoped
public class WindowProducer {

    @Produces
    @Singleton
    EvidenceWindow window(AiopsConfig config, ObjectMapper json, MeterRegistry registry) {
        AiopsConfig.Window w = config.window();
        EvidenceWindow window = new EvidenceWindow(json, Clock.systemUTC(), w.age(), w.maxEvents(), w.maxPerKey());
        Gauge.builder("aiops_window_events", window, EvidenceWindow::size)
            .description("Events held in the Kafka evidence window").register(registry);
        Gauge.builder("aiops_window_oldest_age_seconds", window, EvidenceWindow::oldestAgeSeconds)
            .description("Age of the oldest event in the window").register(registry);
        return window;
    }
}
