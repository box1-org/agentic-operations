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

    public static String derive(JsonNode order, Duration threshold, Instant now) {
        if (order == null) {
            return NONE;
        }
        String state = order.path("state").asText("");
        if ("EXCEPTION".equals(state)) {
            return ORDER_EXCEPTION;
        }
        if ("FULFILLMENT_REQUESTED".equals(state)) {
            try {
                Instant updated = Instant.parse(order.path("updatedAt").asText());
                if (updated.plus(threshold).isBefore(now)) {
                    return AWAITING_ERP_TOO_LONG;
                }
            } catch (DateTimeParseException e) {
                return NONE;
            }
        }
        return NONE;
    }
}
