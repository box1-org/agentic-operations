package org.box1.aiops.window;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Bounded in-memory window of recent domain events (D-007). Read-only evidence: nothing here is authoritative, and
 * an empty window means "no recent events", not an error. Thread-safe; all state changes hold the instance lock.
 */
public class EvidenceWindow {
    public static final int MAX_RECORD_BYTES = 65_536;
    static final int MAX_PAYLOAD_STRING = 128;
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[1-9][0-9]*$");
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** Event types kept as evidence. Readings are dropped for volume; everything else unknown is skipped. */
    static final Set<String> KEPT = Set.of("order.placed.v1", "order.state-changed.v1", "fulfillment.requested.v1",
        "inventory.reserved.v1", "inventory.rejected.v1", "inventory.reservation-expired.v1", "shipment.confirmed.v1",
        "shipment.failed.v1", "equipment.threshold-exceeded.v1", "notification.delivered.v1",
        "notification.failed.v1");
    static final Set<String> FILTERED = Set.of("equipment.reading.v1", "notification.requested.v1");

    public enum Outcome {
        ACCEPTED, DUPLICATE, FILTERED, SKIPPED
    }

    /** Result of one record; {@code reason} is a fixed code for metrics and logs, never record content. */
    public record Result(Outcome outcome, String reason) {
    }

    private final ObjectMapper json;
    private final Clock clock;
    private final Duration age;
    private final int maxEvents;
    private final int maxPerKey;
    private final Deque<WindowEvent> all = new ArrayDeque<>();
    private final Map<String, Deque<WindowEvent>> byKey = new HashMap<>();
    private final Set<String> seen = new LinkedHashSet<>();

    public EvidenceWindow(ObjectMapper json, Clock clock, Duration age, int maxEvents, int maxPerKey) {
        this.json = json;
        this.clock = clock;
        this.age = age;
        this.maxEvents = maxEvents;
        this.maxPerKey = maxPerKey;
    }

    public Result accept(byte[] value, String topic, int partition, long offset) {
        if (value == null || value.length == 0) {
            return skipped("empty");
        }
        if (value.length > MAX_RECORD_BYTES) {
            return skipped("oversize");
        }
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (Exception e) {
            return skipped("unparseable");
        }
        if (root == null || !root.isObject()) {
            return skipped("not_object");
        }
        String eventId = text(root, "eventId");
        String eventType = text(root, "eventType");
        String aggregateId = text(root, "aggregateId");
        String correlationId = text(root, "correlationId");
        String occurred = text(root, "occurredAt");
        JsonNode payload = root.get("payload");
        if (eventId == null || !isUuid(eventId) || eventType == null || !EVENT_TYPE.matcher(eventType).matches()
            || !bounded(aggregateId) || !bounded(correlationId) || occurred == null || payload == null
            || !payload.isObject()) {
            return skipped("invalid_envelope");
        }
        if (FILTERED.contains(eventType)) {
            return new Result(Outcome.FILTERED, "not_evidence");
        }
        if (!KEPT.contains(eventType)) {
            return skipped("unknown_type");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(occurred);
        } catch (DateTimeParseException e) {
            return skipped("invalid_envelope");
        }
        Instant cutoff = clock.instant().minus(age);
        if (occurredAt.isBefore(cutoff)) {
            return new Result(Outcome.FILTERED, "older_than_window");
        }
        String dedupeKey = eventId;
        JsonNode alertId = payload.get("alertId");
        if ("equipment.threshold-exceeded.v1".equals(eventType) && alertId != null && alertId.isTextual()) {
            dedupeKey = "alert:" + alertId.textValue();
        }
        String traceparent = text(root, "traceparent");
        var tp = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        String traceId = tp != null && tp.matches() ? tp.group(1) : null;
        WindowEvent event = new WindowEvent(eventId, eventType, aggregateId, occurredAt, correlationId, traceId,
            payload((ObjectNode) payload), topic, partition, offset);
        synchronized (this) {
            if (!seen.add(dedupeKey)) {
                return new Result(Outcome.DUPLICATE, "duplicate");
            }
            all.addLast(event);
            byKey.computeIfAbsent(aggregateId, k -> new ArrayDeque<>()).addLast(event);
            Deque<WindowEvent> keyed = byKey.get(aggregateId);
            while (keyed.size() > maxPerKey) {
                all.remove(keyed.removeFirst());
            }
            prune(cutoff);
        }
        return new Result(Outcome.ACCEPTED, "accepted");
    }

    /** Newest last; at most {@code limit} events whose aggregate or correlation is {@code key}. */
    public synchronized List<WindowEvent> forKey(String key, int limit) {
        Instant cutoff = clock.instant().minus(age);
        prune(cutoff);
        Deque<WindowEvent> keyed = byKey.getOrDefault(key, new ArrayDeque<>());
        List<WindowEvent> out = new ArrayList<>();
        for (WindowEvent e : keyed) {
            if (!e.occurredAt().isBefore(cutoff)) {
                out.add(e);
            }
        }
        int from = Math.max(0, out.size() - limit);
        return Collections.unmodifiableList(out.subList(from, out.size()));
    }

    public synchronized int size() {
        return all.size();
    }

    /** Age of the oldest event in seconds, or 0 when empty. */
    public synchronized long oldestAgeSeconds() {
        WindowEvent first = all.peekFirst();
        return first == null ? 0 : Math.max(0, Duration.between(first.occurredAt(), clock.instant()).toSeconds());
    }

    public Duration age() {
        return age;
    }

    private void prune(Instant cutoff) {
        Iterator<WindowEvent> it = all.iterator();
        while (it.hasNext()) {
            WindowEvent e = it.next();
            if (all.size() > maxEvents || e.occurredAt().isBefore(cutoff)) {
                it.remove();
                Deque<WindowEvent> keyed = byKey.get(e.aggregateId());
                if (keyed != null) {
                    keyed.remove(e);
                    if (keyed.isEmpty()) {
                        byKey.remove(e.aggregateId());
                    }
                }
            } else if (all.size() <= maxEvents) {
                break;
            }
        }
        while (seen.size() > maxEvents * 2) {
            Iterator<String> s = seen.iterator();
            s.next();
            s.remove();
        }
    }

    /** Top-level scalar payload fields only; long strings are dropped, not cut, since contract IDs are short. */
    private static ObjectNode payload(ObjectNode source) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        source.fields().forEachRemaining(field -> {
            JsonNode v = field.getValue();
            if (v.isContainerNode() || v.isNull() || field.getKey().length() > 64) {
                return;
            }
            if (v.isTextual() && v.textValue().length() > MAX_PAYLOAD_STRING) {
                return;
            }
            out.set(field.getKey(), v);
        });
        return out;
    }

    private static Result skipped(String reason) {
        return new Result(Outcome.SKIPPED, reason);
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static boolean bounded(String s) {
        return s != null && !s.isEmpty() && s.length() <= 128;
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return s.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
