package org.box1.aiops.window;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** An envelope from a domain topic, with a payload reduced to bounded scalar fields. Untrusted data. */
public record WindowEvent(String eventId, String eventType, String aggregateId, Instant occurredAt,
        String correlationId, String traceId, ObjectNode payload, String topic, int partition, long offset) {
}
