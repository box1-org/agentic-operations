package org.box1.aiops.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.box1.aiops.evidence.Fetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Upstream bodies and event envelopes shaped like the real services and box1-ops-contracts. */
public final class Fixtures {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private Fixtures() {
    }

    public static String newOrderId() {
        return "ord_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static ObjectNode order(String orderId, String state, Duration age, String reservationId) {
        ObjectNode o = JSON.createObjectNode();
        o.put("orderId", orderId).put("state", state).put("customerId", "cust_picard").put("sku", "CAT-SPOT-BOWL")
            .put("quantity", 1).put("siteId", "site_spacedock").put("statusReason", "FULFILLMENT_REQUESTED")
            .put("createdAt", Instant.now().minus(age).minusSeconds(60).toString())
            .put("updatedAt", Instant.now().minus(age).toString()).put("correlationId", "corr-" + orderId)
            .put("traceId", TRACE);
        if (reservationId != null) {
            o.put("reservationId", reservationId);
        }
        return o;
    }

    public static ArrayNode timeline(String orderId) {
        ArrayNode a = JSON.createArrayNode();
        a.addObject().put("sequence", 1).put("kind", "STATE").put("toState", "SUBMITTED")
            .put("occurredAt", Instant.now().minusSeconds(3600).toString()).put("eventType", "order.placed.v1");
        a.addObject().put("sequence", 2).put("kind", "STATE").put("fromState", "RESERVED")
            .put("toState", "FULFILLMENT_REQUESTED").put("occurredAt", Instant.now().minusSeconds(1800).toString())
            .put("eventType", "order.state-changed.v1");
        return a;
    }

    public static ObjectNode fulfillment(String fulfillmentId, String orderId) {
        ObjectNode f = JSON.createObjectNode();
        f.put("fulfillmentRequestId", fulfillmentId).put("orderId", orderId).put("acceptance", "ACCEPTED")
            .put("dispatch", "UPLOADED").put("erpAcknowledgement", "PENDING").put("outcome", "PENDING")
            .put("updatedAt", Instant.now().minusSeconds(1700).toString())
            .put("lastError", "ERP AUTO processing delayed");
        return f;
    }

    public static ObjectNode reservation(String reservationId, String orderId) {
        ObjectNode r = JSON.createObjectNode();
        r.put("reservationId", reservationId).put("orderId", orderId).put("siteId", "site_spacedock")
            .put("sku", "CAT-SPOT-BOWL").put("quantity", 1).put("status", "HELD")
            .put("updatedAt", Instant.now().minusSeconds(1800).toString());
        return r;
    }

    public static ObjectNode info(String service) {
        return JSON.createObjectNode().put("name", service).put("version", "1.0.0")
            .put("gitRef", "0123456789abcdef0123456789abcdef01234567");
    }

    public static byte[] event(String type, String aggregateId, ObjectNode payload) {
        ObjectNode e = JSON.createObjectNode();
        e.put("eventId", UUID.randomUUID().toString()).put("eventType", type).put("schemaVersion", 1)
            .put("aggregateId", aggregateId).put("occurredAt", Instant.now().minusSeconds(1200).toString())
            .put("correlationId", "corr-" + aggregateId).put("causationId", "cause-1")
            .put("traceparent", "00-" + TRACE + "-00f067aa0ba902b7-01");
        e.set("payload", payload);
        return e.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An ERP slowdown: order awaiting the ERP for 30 minutes, a retryable shipment failure, everything readable. */
    public static void erpSlowdown(String orderId, String fulfillmentId, String reservationId) {
        FakeUpstreams.ok(FakeUpstreams.ORDERS, orderId,
            order(orderId, "FULFILLMENT_REQUESTED", Duration.ofMinutes(30), reservationId));
        FakeUpstreams.ok(FakeUpstreams.TIMELINES, orderId, timeline(orderId));
        FakeUpstreams.ok(FakeUpstreams.FULFILLMENTS, fulfillmentId, fulfillment(fulfillmentId, orderId));
        FakeUpstreams.ok(FakeUpstreams.RESERVATIONS, reservationId, reservation(reservationId, orderId));
        for (String s : new String[] {"order-service", "inventory-service", "fulfillment-adapter"}) {
            FakeUpstreams.ok(FakeUpstreams.INFOS, s, info(s));
        }
    }

    public static Fetch status(Fetch.Status status) {
        return Fetch.of(status, "fixture " + status.name().toLowerCase());
    }
}
