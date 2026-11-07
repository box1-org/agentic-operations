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

    /**
     * Event types kept as evidence, each with its payload fields from box1-ops-contracts {@code events/v1} (bb381b0).
     * Only these fields are stored (review S-2, S-3). Readings are dropped for volume; unknown types are skipped.
     */
    static final Map<String, Set<String>> KEPT = Map.ofEntries(
        Map.entry("order.placed.v1", Set.of("customerId", "orderId", "quantity", "siteId", "sku")),
        Map.entry("order.state-changed.v1", Set.of("changedAt", "fromState", "orderId", "reasonCode", "toState")),
        Map.entry("fulfillment.requested.v1",
            Set.of("fulfillmentRequestId", "orderId", "quantity", "reservationId", "siteId", "sku")),
        Map.entry("inventory.reserved.v1",
            Set.of("expiresAt", "orderId", "quantity", "reservationId", "siteId", "sku")),
        Map.entry("inventory.rejected.v1", Set.of("orderId", "quantity", "reasonCode", "siteId", "sku")),
        Map.entry("inventory.reservation-expired.v1", Set.of("expiredAt", "orderId", "reservationId")),
        Map.entry("shipment.confirmed.v1", Set.of("fulfillmentRequestId", "orderId", "shipmentId", "shippedAt")),
        Map.entry("shipment.failed.v1", Set.of("fulfillmentRequestId", "orderId", "reasonCode", "retryable")),
        Map.entry("equipment.threshold-exceeded.v1", Set.of("alertId", "assetId", "measuredAt", "metricName",
            "severity", "siteId", "sourceEventId", "threshold", "unit", "value")),
        Map.entry("notification.delivered.v1", Set.of("attemptCount", "channel", "deliveredAt", "noticeType",
            "notificationId", "orderId", "provider", "templateVersion")),
        Map.entry("notification.failed.v1", Set.of("attemptCount", "channel", "failedAt", "failureCategory",
            "failureCode", "httpStatus", "noticeType", "notificationId", "orderId", "provider", "templateVersion")));
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
    private final Map<String, Deque<WindowEvent>> byCorrelation = new HashMap<>();
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
        String causationId = text(root, "causationId");
        JsonNode schemaVersion = root.get("schemaVersion");
        JsonNode payload = root.get("payload");
        if (eventId == null || !isUuid(eventId) || eventType == null || !EVENT_TYPE.matcher(eventType).matches()
            || !bounded(aggregateId) || !bounded(correlationId) || !bounded(causationId) || schemaVersion == null
            || !schemaVersion.isInt() || schemaVersion.intValue() != 1 || occurred == null || payload == null
            || !payload.isObject()) {
            return skipped("invalid_envelope");
        }
        if (FILTERED.contains(eventType)) {
            return new Result(Outcome.FILTERED, "not_evidence");
        }
        if (!KEPT.containsKey(eventType)) {
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
            payload((ObjectNode) payload, KEPT.get(eventType)), topic, partition, offset);
        synchronized (this) {
            if (!seen.add(dedupeKey)) {
                return new Result(Outcome.DUPLICATE, "duplicate");
            }
            all.addLast(event);
            byKey.computeIfAbsent(aggregateId, k -> new ArrayDeque<>()).addLast(event);
            byCorrelation.computeIfAbsent(correlationId, k -> new ArrayDeque<>()).addLast(event);
            Deque<WindowEvent> keyed = byKey.get(aggregateId);
            while (keyed.size() > maxPerKey) {
                evict(oldest(keyed));
            }
            prune(cutoff);
        }
        return new Result(Outcome.ACCEPTED, "accepted");
    }

    /** Newest last; at most {@code limit} events whose aggregate (order or asset ID) is {@code key}. */
    public synchronized List<WindowEvent> forKey(String key, int limit) {
        return select(byKey.get(key), limit);
    }

    /** Newest last; at most {@code limit} events that share {@code correlationId}. */
    public synchronized List<WindowEvent> forCorrelation(String correlationId, int limit) {
        return select(byCorrelation.get(correlationId), limit);
    }

    private List<WindowEvent> select(Deque<WindowEvent> source, int limit) {
        Instant cutoff = clock.instant().minus(age);
        prune(cutoff);
        Deque<WindowEvent> keyed = source == null ? new ArrayDeque<>() : source;
        List<WindowEvent> out = new ArrayList<>();
        for (WindowEvent e : keyed) {
            if (!e.occurredAt().isBefore(cutoff)) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparing(WindowEvent::occurredAt));
        int from = Math.max(0, out.size() - limit);
        return Collections.unmodifiableList(out.subList(from, out.size()));
    }

    public synchronized int size() {
        return all.size();
    }

    /** Age of the oldest event in seconds, or 0 when empty. */
    public synchronized long oldestAgeSeconds() {
        WindowEvent first = all.isEmpty() ? null : oldest(all);
        return first == null ? 0 : Math.max(0, Duration.between(first.occurredAt(), clock.instant()).toSeconds());
    }

    public Duration age() {
        return age;
    }

    /**
     * Removes expired events by occurrence time, then the oldest while over the size bound. Events from five topics
     * interleave, so arrival order is not time order (review M-4).
     */
    private void prune(Instant cutoff) {
        List<WindowEvent> expired = new ArrayList<>();
        for (WindowEvent e : all) {
            if (e.occurredAt().isBefore(cutoff)) {
                expired.add(e);
            }
        }
        expired.forEach(this::evict);
        while (all.size() > maxEvents) {
            evict(oldest(all));
        }
        while (seen.size() > maxEvents * 2) {
            Iterator<String> s = seen.iterator();
            s.next();
            s.remove();
        }
    }

    private void evict(WindowEvent e) {
        all.remove(e);
        removeFrom(byKey, e.aggregateId(), e);
        removeFrom(byCorrelation, e.correlationId(), e);
    }

    private static void removeFrom(Map<String, Deque<WindowEvent>> index, String key, WindowEvent e) {
        Deque<WindowEvent> d = index.get(key);
        if (d != null) {
            d.remove(e);
            if (d.isEmpty()) {
                index.remove(key);
            }
        }
    }

    private static WindowEvent oldest(Iterable<WindowEvent> events) {
        WindowEvent min = null;
        for (WindowEvent e : events) {
            if (min == null || e.occurredAt().isBefore(min.occurredAt())) {
                min = e;
            }
        }
        return min;
    }

    /** Contract fields of this event type only, as bounded scalars; long strings are dropped, not cut. */
    private static ObjectNode payload(ObjectNode source, Set<String> allowed) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String field : allowed) {
            JsonNode v = source.get(field);
            if (v == null || v.isContainerNode() || v.isNull()) {
                continue;
            }
            if (v.isTextual() && v.textValue().length() > MAX_PAYLOAD_STRING) {
                continue;
            }
            out.set(field, v);
        }
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
