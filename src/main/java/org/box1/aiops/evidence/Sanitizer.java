package org.box1.aiops.evidence;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Keeps only allowlisted scalar fields and bounds every string. Evidence never carries nested upstream objects. */
public final class Sanitizer {
    public static final int MAX_STRING = 256;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static final Set<String> ORDER = Set.of("orderId", "state", "customerId", "sku", "quantity", "siteId",
        "reservationId", "statusReason", "createdAt", "updatedAt", "correlationId", "traceId");
    public static final Set<String> TIMELINE = Set.of("sequence", "kind", "fromState", "toState", "reasonCode",
        "occurredAt", "eventId", "eventType", "correlationId", "traceId");
    public static final Set<String> FULFILLMENT = Set.of("fulfillmentRequestId", "orderId", "reservationId", "siteId",
        "sku", "quantity", "origin", "backend", "acceptance", "acceptedAt", "dispatch", "uploads", "resends",
        "failedUploadAttempts", "lastUploadAt", "lastError", "needsAttentionReason", "erpAcknowledgement",
        "acknowledgedAt", "outcome", "revision", "shipmentId", "statusAt", "reasonCode", "updatedAt");
    public static final Set<String> RESERVATION = Set.of("reservationId", "orderId", "siteId", "sku", "quantity",
        "status", "expiresAt", "createdAt", "updatedAt");
    public static final Set<String> INFO = Set.of("name", "version", "gitRef", "deliveryMode");

    private Sanitizer() {
    }

    public static ObjectNode object(JsonNode source, Set<String> allowed) {
        ObjectNode out = F.objectNode();
        if (source == null || !source.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (allowed.contains(field.getKey())) {
                JsonNode value = scalar(field.getValue());
                if (value != null) {
                    out.set(field.getKey(), value);
                }
            }
        }
        return out;
    }

    public static ArrayNode array(JsonNode source, Set<String> allowed, int max) {
        ArrayNode out = F.arrayNode();
        if (source == null || !source.isArray()) {
            return out;
        }
        int start = Math.max(0, source.size() - max);
        for (int i = start; i < source.size(); i++) {
            out.add(object(source.get(i), allowed));
        }
        return out;
    }

    /** Strings are cut to {@link #MAX_STRING}; objects and arrays are dropped. */
    public static JsonNode scalar(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        if (value.isTextual() && value.textValue().length() > MAX_STRING) {
            return F.textNode(value.textValue().substring(0, MAX_STRING) + "...");
        }
        return value;
    }
}
