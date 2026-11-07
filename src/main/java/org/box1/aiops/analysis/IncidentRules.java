package org.box1.aiops.analysis;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;

/** Same derivation as operations-console {@code Incidents.java} (D-002); incidents are never stored. */
public final class IncidentRules {
    public static final String ORDER_EXCEPTION = "ORDER_EXCEPTION";
    public static final String AWAITING_ERP_TOO_LONG = "AWAITING_ERP_TOO_LONG";
    public static final String NONE = "NONE";

    private IncidentRules() {
    }

    /**
     * Mirrors the console's {@code Incidents.current()}: an order still awaiting the ERP is an active
     * {@code AWAITING_ERP_TOO_LONG} incident even below the threshold (review A-4). {@code threshold} is kept for
     * the console's list view, where it still decides whether the incident is raised.
     */
    public static String derive(JsonNode order, Duration threshold, Instant now) {
        if (order == null) {
            return NONE;
        }
        String state = order.path("state").asText("");
        if ("EXCEPTION".equals(state)) {
            return ORDER_EXCEPTION;
        }
        if ("FULFILLMENT_REQUESTED".equals(state)) {
            return AWAITING_ERP_TOO_LONG;
        }
        return NONE;
    }

    /** Minutes the order has been awaiting the ERP, or null when it is not awaiting it. */
    public static Long minutesAwaitingErp(JsonNode order, Instant now) {
        if (order == null || !"FULFILLMENT_REQUESTED".equals(order.path("state").asText(""))) {
            return null;
        }
        try {
            return Math.max(0, Duration.between(Instant.parse(order.path("updatedAt").asText()), now).toMinutes());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
