package org.box1.aiops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.box1.aiops.support.Api;
import org.box1.aiops.support.FakeUpstreams;
import org.box1.aiops.support.Fixtures;
import org.box1.aiops.support.Mcp;
import org.box1.aiops.support.ScriptedModel;
import org.box1.aiops.window.EvidenceWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;

/** MCP surface (D-004): the catalog is read-only plus analysis, and every call is authorized like REST. */
@QuarkusTest
class McpToolsTest {
    static final Set<String> CATALOG = Set.of("get_order_timeline", "get_fulfillment_status",
        "get_inventory_reservation", "list_recent_events", "list_telemetry_alerts", "get_deployed_revision",
        "analyze_incident", "get_analysis", "get_trace", "search_logs", "list_service_alerts");

    @Inject
    EvidenceWindow window;

    String orderId;
    String fulfillmentId;

    @BeforeEach
    void setUp() {
        ScriptedModel.reset();
        FakeUpstreams.CALLS.clear();
        orderId = Fixtures.newOrderId();
        fulfillmentId = "ful_" + orderId.substring(4);
        Fixtures.erpSlowdown(orderId, fulfillmentId, "res_" + orderId.substring(4));
        window.accept(Fixtures.event("fulfillment.requested.v1", orderId, Fixtures.JSON.createObjectNode()
            .put("orderId", orderId).put("fulfillmentRequestId", fulfillmentId)), "t", 0, 1);
    }

    @Test
    void anonymousClientsCannotConnect() {
        Mcp.initializeRaw().then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void theCatalogHoldsOnlyReadOnlyEvidenceAndAnalysisTools() {
        JsonPath list = Mcp.connect().request("tools/list", null);
        Set<String> names = new TreeSet<>(list.getList("result.tools.name", String.class));
        assertEquals(new TreeSet<>(CATALOG), names);
        for (String forbidden : List.of("exec", "shell", "delete", "patch", "apply", "rollback", "execute", "fetch")) {
            assertTrue(names.stream().noneMatch(n -> n.contains(forbidden)), forbidden);
        }
        JsonPath caps = Mcp.initializeRaw().then().statusCode(200).extract().jsonPath();
        assertFalse(caps.getMap("result.capabilities").containsKey("resources"));
        assertFalse(caps.getMap("result.capabilities").containsKey("prompts"));
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void viewerReadsEvidenceLabelledUntrusted() {
        Mcp mcp = Mcp.connect();
        JsonPath timeline = Mcp.result(mcp.call("get_order_timeline", Mcp.args("orderId", orderId)));
        assertEquals("ORDER_TIMELINE", timeline.getString("type"));
        assertEquals("OK", timeline.getString("retrievalStatus"));
        JsonPath fulfillment = Mcp.result(mcp.call("get_fulfillment_status", Mcp.args("orderId", orderId)));
        assertEquals("fulfillment-adapter:/api/fulfillments/" + fulfillmentId, fulfillment.getString("link"));
        JsonPath events = Mcp.result(mcp.call("list_recent_events", Mcp.args("key", orderId)));
        assertEquals(1, events.getList("data").size());
        JsonPath byCorrelation = Mcp.result(mcp.call("list_recent_events",
            Mcp.args("correlationId", "corr-" + orderId)));
        assertEquals(1, byCorrelation.getList("data").size());
        JsonPath alerts = Mcp.result(mcp.call("list_telemetry_alerts", null));
        assertEquals("UNAVAILABLE", alerts.getString("retrievalStatus"));
        JsonPath revision = Mcp.result(mcp.call("get_deployed_revision", Mcp.args("service", "order-service")));
        assertEquals("0123456789abcdef0123456789abcdef01234567", revision.getString("data.gitRef"));
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void viewerCannotStartAnAnalysisThroughMcp() {
        JsonPath r = Mcp.connect().call("analyze_incident", Mcp.args("incidentId", "inc_" + orderId));
        assertTrue(r.getBoolean("result.isError"));
        assertTrue(r.getString("result.content[0].text").startsWith("forbidden"));
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }

    @Test
    @TestSecurity(user = "nobody", roles = "customer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void callersWithoutAnAiopsRoleCannotConnect() {
        Mcp.initializeRaw().then().statusCode(403);
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "some-other-client"))
    void tokensFromUnacceptedClientsGetNoEvidence() {
        JsonPath r = Mcp.connect().call("get_order_timeline", Mcp.args("orderId", orderId));
        assertTrue(r.getBoolean("result.isError"));
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void invalidArgumentsAndUnknownToolsAreRejectedWithoutReads() {
        Mcp mcp = Mcp.connect();
        for (String bad : List.of("ord_../../admin", "https://evil.example/x", "ord_a b", "")) {
            JsonPath r = mcp.call("get_order_timeline", Mcp.args("orderId", bad));
            assertTrue(r.getBoolean("result.isError"), bad);
        }
        assertTrue(mcp.call("get_deployed_revision", Mcp.args("service", "kube-apiserver"))
            .getBoolean("result.isError"));
        JsonPath unknown = mcp.call("delete_order", Mcp.args("orderId", orderId));
        assertTrue(unknown.get("error") != null || unknown.getBoolean("result.isError"));
        assertTrue(FakeUpstreams.CALLS.isEmpty(), FakeUpstreams.CALLS.toString());
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void restAndMcpUseTheSameEvidenceAndDecisions() {
        JsonPath viaRest = Api.await(Api.submit(orderId));
        Mcp mcp = Mcp.connect();
        String id = Mcp.result(mcp.call("analyze_incident", Mcp.args("incidentId", "inc_" + orderId)))
            .getString("analysisId");
        JsonPath viaMcp = Api.await(id);
        JsonPath readViaMcp = Mcp.result(mcp.call("get_analysis", Mcp.args("analysisId", id)));
        assertEquals(viaMcp.getString("status"), readViaMcp.getString("status"));
        assertEquals(viaRest.getString("status"), viaMcp.getString("status"));
        assertEquals(viaRest.getString("incidentType"), viaMcp.getString("incidentType"));
        assertEquals(shape(viaRest), shape(viaMcp));
        assertEquals(viaRest.getList("unknowns"), viaMcp.getList("unknowns"));
    }

    private static List<String> shape(JsonPath analysis) {
        List<Map<String, Object>> evidence = analysis.getList("evidence");
        return evidence.stream().map(e -> e.get("ref") + ":" + e.get("type") + ":" + e.get("retrievalStatus") + ":"
            + e.get("link")).collect(Collectors.toList());
    }
}
