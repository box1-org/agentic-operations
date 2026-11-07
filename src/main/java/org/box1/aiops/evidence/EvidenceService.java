package org.box1.aiops.evidence;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.Ids;
import org.box1.aiops.security.Guard;
import org.box1.aiops.window.EvidenceWindow;
import org.box1.aiops.window.WindowEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Read-only evidence (D-006). The service, never the model, decides what is fetched, and only for the requested order
 * or asset. Public single-source methods are guarded and back the MCP evidence tools; {@link #gather} is called by
 * the analysis service after it has authorized the caller.
 */
@ApplicationScoped
public class EvidenceService {
    public static final String ORDER = "ORDER";
    public static final String TIMELINE = "ORDER_TIMELINE";
    public static final String EVENTS = "KAFKA_EVENTS";
    public static final String FULFILLMENT = "FULFILLMENT";
    public static final String RESERVATION = "RESERVATION";
    public static final String REVISION = "DEPLOYED_REVISION";
    public static final String TRACE_REFERENCE = "TRACE_REFERENCE";
    public static final String TELEMETRY = "TELEMETRY_ALERTS";
    public static final String TRACE_CONTENT = "TRACE_CONTENT";
    public static final String METRICS = "METRICS_AND_ALERTS";
    public static final String LOGS = "LOGS";

    /** Sources expected to work in v1; a failure here makes an analysis DEGRADED. */
    public static final Set<String> CORE = Set.of(ORDER, TIMELINE, FULFILLMENT, RESERVATION, REVISION);

    static final int EVENTS_DEFAULT = 20;
    static final int EVENTS_MAX = 50;
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Order evidence with the bundle; {@code order} is null unless the order read succeeded. */
    public record Bundle(JsonNode order, Fetch.Status orderStatus, List<EvidenceItem> items) {
    }

    private final Upstreams upstreams;
    private final EvidenceWindow window;
    private final Guard guard;
    private final ObjectMapper json;
    private final AiopsConfig config;
    private final MeterRegistry registry;
    private final Clock clock = Clock.systemUTC();

    @Inject
    public EvidenceService(Upstreams upstreams, EvidenceWindow window, Guard guard, ObjectMapper json,
            AiopsConfig config, MeterRegistry registry) {
        this.upstreams = upstreams;
        this.window = window;
        this.guard = guard;
        this.json = json;
        this.config = config;
        this.registry = registry;
    }

    // ---- guarded single-source reads (MCP evidence tools) --------------------------------------------------------

    public EvidenceItem orderTimeline(String orderId) {
        guard.requireViewer();
        Ids.order(orderId);
        return timeline(orderId).withRef("ev_1");
    }

    public EvidenceItem fulfillmentStatus(String orderId) {
        guard.requireViewer();
        Ids.order(orderId);
        return fulfillment(orderId, window.forKey(orderId, EVENTS_MAX)).withRef("ev_1");
    }

    public EvidenceItem inventoryReservation(String orderId) {
        guard.requireViewer();
        Ids.order(orderId);
        Fetch order = counted(ORDER, upstreams.order(orderId));
        return reservation(orderId, order).withRef("ev_1");
    }

    public EvidenceItem recentEvents(String key, Integer limit) {
        guard.requireViewer();
        Ids.windowKey(key);
        int size = limit == null ? EVENTS_DEFAULT : Math.max(1, Math.min(EVENTS_MAX, limit));
        return events(key, window.forKey(key, size)).withRef("ev_1");
    }

    public EvidenceItem deployedRevision(String service) {
        guard.requireViewer();
        Ids.service(service);
        return revision(service).withRef("ev_1");
    }

    public EvidenceItem telemetryAlerts(String assetId) {
        guard.requireViewer();
        if (assetId != null && !assetId.isBlank()) {
            Ids.asset(assetId);
        }
        return telemetryUnavailable().withRef("ev_1");
    }

    // ---- bundle for an incident (caller already authorized) ------------------------------------------------------

    public Bundle gather(String orderId, int maxBytes) {
        List<EvidenceItem> items = new ArrayList<>();
        Fetch order = counted(ORDER, upstreams.order(orderId));
        items.add(item(ORDER, "order-service", "order-service:/api/v1/orders/" + orderId, order,
            b -> Sanitizer.object(b, Sanitizer.ORDER), b -> instant(b, "updatedAt")));
        items.add(timeline(orderId));
        List<WindowEvent> recent = window.forKey(orderId, EVENTS_MAX);
        items.add(events(orderId, recent));
        items.add(fulfillment(orderId, recent));
        items.add(reservation(orderId, order));
        for (String service : List.of("order-service", "inventory-service", "fulfillment-adapter")) {
            items.add(revision(service));
        }
        String traceId = order.isOk() ? text(order.body(), "traceId") : null;
        if (traceId != null && TRACE_ID.matcher(traceId).matches()) {
            items.add(new EvidenceItem(null, TRACE_REFERENCE, "order-service", instant(order.body(), "createdAt"),
                now(), EvidenceItem.OK, "trace identifier only; contents are not readable in v1",
                "tempo:dev/traces/" + traceId, false, F.objectNode().put("traceId", traceId)));
        }
        items.add(telemetryUnavailable());
        items.add(unavailable(TRACE_CONTENT, "tempo", "no trace read access in v1 (box1-docs#20 Q-6)"));
        items.add(unavailable(METRICS, "thanos-querier", "no metrics or alert read access in v1 (box1-docs#20 Q-6)"));
        items.add(unavailable(LOGS, "none", "no centralized logging (box1-docs#82)"));

        List<EvidenceItem> numbered = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            numbered.add(items.get(i).withRef("ev_" + (i + 1)));
        }
        return new Bundle(order.isOk() ? order.body() : null, order.status(), fitToBudget(numbered, maxBytes));
    }

    // ---- sources -------------------------------------------------------------------------------------------------

    private EvidenceItem timeline(String orderId) {
        int limit = config.evidence().timelineLimit();
        Fetch fetch = counted(TIMELINE, upstreams.timeline(orderId, limit));
        return item(TIMELINE, "order-service", "order-service:/api/v1/orders/" + orderId + "/timeline", fetch,
            b -> Sanitizer.array(b, Sanitizer.TIMELINE, limit), b -> lastInstant(b, "occurredAt"));
    }

    private EvidenceItem events(String key, List<WindowEvent> recent) {
        ArrayNode data = F.arrayNode();
        for (WindowEvent e : recent) {
            ObjectNode o = data.addObject();
            o.put("eventId", e.eventId()).put("eventType", e.eventType()).put("occurredAt", e.occurredAt().toString())
                .put("correlationId", e.correlationId());
            if (e.traceId() != null) {
                o.put("traceId", e.traceId());
            }
            o.set("payload", e.payload());
        }
        Instant newest = recent.isEmpty() ? null : recent.get(recent.size() - 1).occurredAt();
        String detail = recent.isEmpty() ? "no events for this key in the last " + window.age() + " (window is "
            + "in memory and refills after a restart)" : null;
        return new EvidenceItem(null, EVENTS, "kafka-window", newest, now(), EvidenceItem.OK, detail,
            "kafka-window:" + key, false, data);
    }

    private EvidenceItem fulfillment(String orderId, List<WindowEvent> recent) {
        String fulfillmentId = null;
        for (WindowEvent e : recent) {
            JsonNode id = e.payload().get("fulfillmentRequestId");
            if (id != null && Ids.isFulfillment(id.asText())) {
                fulfillmentId = id.asText();
            }
        }
        if (fulfillmentId == null) {
            return new EvidenceItem(null, FULFILLMENT, "fulfillment-adapter", null, now(), "UNAVAILABLE",
                "fulfillmentRequestId for " + orderId + " is not in the event window (box1-docs#78)", null, false,
                null);
        }
        Fetch fetch = counted(FULFILLMENT, upstreams.fulfillment(fulfillmentId));
        return item(FULFILLMENT, "fulfillment-adapter", "fulfillment-adapter:/api/fulfillments/" + fulfillmentId,
            fetch, b -> Sanitizer.object(b, Sanitizer.FULFILLMENT), b -> instant(b, "updatedAt"));
    }

    private EvidenceItem reservation(String orderId, Fetch order) {
        String reservationId = order.isOk() ? text(order.body(), "reservationId") : null;
        if (reservationId == null || !Ids.isReservation(reservationId)) {
            String why = order.isOk() ? "order " + orderId + " has no reservation"
                : "order evidence unavailable (" + order.status() + ")";
            return new EvidenceItem(null, RESERVATION, "inventory-service", null, now(), "UNAVAILABLE", why, null,
                false, null);
        }
        Fetch fetch = counted(RESERVATION, upstreams.reservation(reservationId));
        return item(RESERVATION, "inventory-service", "inventory-service:/api/v1/reservations/" + reservationId,
            fetch, b -> Sanitizer.object(b, Sanitizer.RESERVATION), b -> instant(b, "updatedAt"));
    }

    private EvidenceItem revision(String service) {
        Fetch fetch = counted(REVISION, upstreams.info(service));
        return item(REVISION, service, service + ":/api/info", fetch, b -> {
            ObjectNode o = Sanitizer.object(b, Sanitizer.INFO);
            o.put("service", service);
            return o;
        }, b -> null);
    }

    private EvidenceItem telemetryUnavailable() {
        return unavailable(TELEMETRY, "asset-service",
            "telemetry-reader grant not provisioned in v1 (box1-docs#20 Q-5); threshold alerts may still appear "
                + "in the event window");
    }

    private EvidenceItem unavailable(String type, String source, String detail) {
        return new EvidenceItem(null, type, source, null, now(), "UNAVAILABLE", detail, null, false, null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private EvidenceItem item(String type, String source, String link, Fetch fetch,
            java.util.function.Function<JsonNode, JsonNode> shape,
            java.util.function.Function<JsonNode, Instant> timestamp) {
        if (!fetch.isOk()) {
            return new EvidenceItem(null, type, source, null, now(), fetch.status().name(), fetch.detail(), link,
                false, null);
        }
        return new EvidenceItem(null, type, source, timestamp.apply(fetch.body()), now(), EvidenceItem.OK, null, link,
            false, shape.apply(fetch.body()));
    }

    private Fetch counted(String type, Fetch fetch) {
        registry.counter("aiops_evidence_fetch_total", "type", type, "status", fetch.status().name()).increment();
        return fetch;
    }

    /** Drops the oldest array entries from the largest items until the bundle fits. */
    List<EvidenceItem> fitToBudget(List<EvidenceItem> items, int maxBytes) {
        List<EvidenceItem> out = new ArrayList<>(items);
        for (int guardLoop = 0; guardLoop < 10_000 && size(out) > maxBytes; guardLoop++) {
            int largest = -1;
            int largestSize = 0;
            for (int i = 0; i < out.size(); i++) {
                JsonNode data = out.get(i).data();
                if (data != null && data.isArray() && data.size() > 0) {
                    int s = bytes(data);
                    if (s > largestSize) {
                        largestSize = s;
                        largest = i;
                    }
                }
            }
            if (largest < 0) {
                break;
            }
            ArrayNode copy = ((ArrayNode) out.get(largest).data()).deepCopy();
            copy.remove(0);
            out.set(largest, out.get(largest).withData(copy, true));
        }
        return out;
    }

    private int size(List<EvidenceItem> items) {
        int total = 0;
        for (EvidenceItem i : items) {
            total += i.data() == null ? 0 : bytes(i.data());
        }
        return total;
    }

    private int bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node).length;
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE / 4;
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant lastInstant(JsonNode array, String field) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        return instant(array.get(array.size() - 1), field);
    }
}
