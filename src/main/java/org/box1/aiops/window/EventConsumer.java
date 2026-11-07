package org.box1.aiops.window;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;

/**
 * Feeds the evidence window. Read-only: offsets are never committed and nothing is written to any topic or DLQ.
 * A record the window cannot use is skipped, counted and logged by position only (D-007).
 */
@io.quarkus.runtime.Startup
@ApplicationScoped
public class EventConsumer {
    private final EvidenceWindow window;
    private final MeterRegistry registry;

    @Inject
    public EventConsumer(EvidenceWindow window, MeterRegistry registry) {
        this.window = window;
        this.registry = registry;
    }

    @Incoming("evidence-events")
    public CompletionStage<Void> consume(Message<byte[]> message) {
        String topic = "unknown";
        int partition = -1;
        long offset = -1;
        var meta = message.getMetadata(IncomingKafkaRecordMetadata.class);
        if (meta.isPresent()) {
            topic = meta.get().getTopic();
            partition = meta.get().getPartition();
            offset = meta.get().getOffset();
        }
        EvidenceWindow.Result result;
        try {
            result = window.accept(message.getPayload(), topic, partition, offset);
        } catch (RuntimeException e) {
            result = new EvidenceWindow.Result(EvidenceWindow.Outcome.SKIPPED, "internal");
        }
        registry.counter("aiops_kafka_records_total", "topic", topic, "outcome",
            result.outcome().name().toLowerCase(), "reason", result.reason()).increment();
        if (result.outcome() == EvidenceWindow.Outcome.SKIPPED) {
            Log.warnf("window skipped record topic=%s partition=%d offset=%d reason=%s", topic, partition, offset,
                result.reason());
        }
        return message.ack();
    }
}
