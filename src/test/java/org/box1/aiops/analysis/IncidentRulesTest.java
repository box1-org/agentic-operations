package org.box1.aiops.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Same rules as operations-console Incidents.java. */
class IncidentRulesTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-07T04:00:00Z");
    static final Duration TEN = Duration.ofMinutes(10);

    static ObjectNode order(String state, Duration age) {
        return JSON.createObjectNode().put("state", state).put("updatedAt", NOW.minus(age).toString());
    }

    @Test
    void derivesTheConsoleIncidentTypes() {
        assertEquals("ORDER_EXCEPTION", IncidentRules.derive(order("EXCEPTION", Duration.ZERO), TEN, NOW));
        assertEquals("AWAITING_ERP_TOO_LONG",
            IncidentRules.derive(order("FULFILLMENT_REQUESTED", Duration.ofMinutes(11)), TEN, NOW));
        assertEquals("NONE", IncidentRules.derive(order("FULFILLMENT_REQUESTED", Duration.ofMinutes(9)), TEN, NOW));
        assertEquals("NONE", IncidentRules.derive(order("SHIPPED", Duration.ofHours(5)), TEN, NOW));
        assertEquals("NONE", IncidentRules.derive(null, TEN, NOW));
    }
}
