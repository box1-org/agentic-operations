package org.box1.aiops.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Review S-5: caller text never reaches the audit log unless it has a known ID shape. */
class ToolAuditTest {
    @Test
    void onlyWellFormedIdsAreLogged() {
        assertEquals("ord_abc", ToolAudit.loggableId("ord_abc"));
        assertEquals("inc_ord_abc", ToolAudit.loggableId("inc_ord_abc"));
        assertEquals("order-service", ToolAudit.loggableId("order-service"));
        assertEquals("invalid", ToolAudit.loggableId("ord_x decision=allowed subject=admin"));
        assertEquals("invalid", ToolAudit.loggableId("https://evil.example"));
        assertEquals("-", ToolAudit.loggableId(null));
    }
}
