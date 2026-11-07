package org.box1.aiops;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.box1.aiops.evidence.Fetch;
import org.box1.aiops.model.ModelResult;
import org.box1.aiops.support.Api;
import org.box1.aiops.support.FakeUpstreams;
import org.box1.aiops.support.Fixtures;
import org.box1.aiops.support.ScriptedModel;
import org.box1.aiops.window.EvidenceWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;

/** REST surface: authorization, incident resolution, citations, typed failure states and the queue bound. */
@QuarkusTest
class AnalysisApiTest {

    @Inject
    EvidenceWindow window;

    String orderId;
    String fulfillmentId;
    String reservationId;

    @BeforeEach
    void setUp() {
        ScriptedModel.reset();
        FakeUpstreams.CALLS.clear();
        orderId = Fixtures.newOrderId();
        fulfillmentId = "ful_" + orderId.substring(4);
        reservationId = "res_" + orderId.substring(4);
        Fixtures.erpSlowdown(orderId, fulfillmentId, reservationId);
        ObjectNode requested = Fixtures.JSON.createObjectNode().put("orderId", orderId)
            .put("reservationId", reservationId).put("fulfillmentRequestId", fulfillmentId)
            .put("siteId", "site_spacedock").put("sku", "CAT-SPOT-BOWL").put("quantity", 1);
        window.accept(Fixtures.event("fulfillment.requested.v1", orderId, requested), "t", 0, 1);
        ObjectNode failed = Fixtures.JSON.createObjectNode().put("orderId", orderId)
            .put("fulfillmentRequestId", fulfillmentId).put("retryable", true).put("reasonCode", "ERP_TIMEOUT");
        window.accept(Fixtures.event("shipment.failed.v1", orderId, failed), "t", 0, 2);
    }

    @Test
    void anonymousCallersAreRejected() {
        given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(401);
        given().get("/api/v1/ai/analyses/ana_0123456789abcdef0123456789abcdef").then().statusCode(401);
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }

    @Test
    @TestSecurity(user = "viewer", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void viewerCannotStartAnAnalysis() {
        given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(403)
            .body("code", is("FORBIDDEN"));
        assertTrue(FakeUpstreams.CALLS.isEmpty(), "no upstream read before authorization");
    }

    @Test
    @TestSecurity(user = "nobody", roles = "customer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void otherRolesAreRejected() {
        given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(403);
        given().get("/api/v1/ai/analyses/ana_0123456789abcdef0123456789abcdef").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "some-other-client"))
    void tokensFromUnacceptedClientsAreRejected() {
        given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(403)
            .body("detail", containsString("client"));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void tokensFromTheCliClientAreAccepted() {
        Api.await(Api.submit(orderId));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void invalidIdentifiersAreRejectedBeforeAnyRead() {
        given().post("/api/v1/ai/incidents/ord_123/analyze").then().statusCode(400)
            .body("code", is("INVALID_INCIDENT_ID"));
        given().post("/api/v1/ai/incidents/inc_ord_bad$name/analyze").then().statusCode(400);
        given().get("/api/v1/ai/analyses/not-an-id").then().statusCode(400).body("code", is("INVALID_ANALYSISID"));
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void unknownIncidentIsNotFoundAndNeverReachesTheModel() {
        given().post("/api/v1/ai/incidents/inc_ord_doesnotexist/analyze").then().statusCode(404)
            .body("code", is("INCIDENT_NOT_FOUND"));
        assertTrue(ScriptedModel.REQUESTS.isEmpty());
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void unknownAnalysisIsNotFound() {
        given().get("/api/v1/ai/analyses/ana_0123456789abcdef0123456789abcdef").then().statusCode(404)
            .body("code", is("ANALYSIS_NOT_FOUND")).body("detail", containsString("restart"));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void erpSlowdownReturnsCitedErpEvidenceAndStatesWhatIsUnavailable() {
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("COMPLETED", a.getString("status"));
        assertEquals("AWAITING_ERP_TOO_LONG", a.getString("incidentType"));
        List<Map<String, Object>> evidence = a.getList("evidence");
        Map<String, Map<String, Object>> byType = new java.util.HashMap<>();
        evidence.forEach(e -> byType.put((String) e.get("type"), e));
        assertEquals("OK", byType.get("FULFILLMENT").get("retrievalStatus"));
        assertEquals("fulfillment-adapter:/api/fulfillments/" + fulfillmentId, byType.get("FULFILLMENT").get("link"));
        assertEquals("tempo:dev/traces/" + Fixtures.TRACE, byType.get("TRACE_REFERENCE").get("link"));
        assertEquals("OK", byType.get("KAFKA_EVENTS").get("retrievalStatus"));
        assertEquals("OK", byType.get("DEPLOYED_REVISION").get("retrievalStatus"));
        for (String t : List.of("LOGS", "TELEMETRY_ALERTS", "TRACE_CONTENT", "METRICS_AND_ALERTS")) {
            assertEquals("UNAVAILABLE", byType.get(t).get("retrievalStatus"), t);
            assertTrue(a.getList("unknowns", String.class).stream().anyMatch(u -> u.contains(t)), t);
        }
        Set<String> refs = new java.util.HashSet<>();
        evidence.forEach(e -> {
            refs.add((String) e.get("ref"));
            assertTrue(e.get("timestamp") != null || !"OK".equals(e.get("retrievalStatus"))
                || "KAFKA_EVENTS".equals(e.get("type")) || "DEPLOYED_REVISION".equals(e.get("type")));
            assertTrue(e.containsKey("source") && e.containsKey("retrievedAt"));
        });
        List<Map<String, Object>> findings = a.getList("findings");
        assertFalse(findings.isEmpty());
        for (Map<String, Object> f : findings) {
            @SuppressWarnings("unchecked")
            List<String> cited = (List<String>) f.get("evidenceRefs");
            assertFalse(cited.isEmpty());
            assertTrue(refs.containsAll(cited));
        }
        a.getList("suggestedActions", Map.class).forEach(x -> assertEquals(false, x.get("executable")));
        assertEquals("scripted-test-model", a.getString("model.modelId"));
        assertEquals("32k", a.getString("model.contextProfile"));
        String system = ScriptedModel.REQUESTS.get(0).system();
        assertEquals(org.box1.aiops.analysis.Prompt.SYSTEM, system);
        assertFalse(system.contains(orderId), "evidence never enters the system message");
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void anAbsentSourceIsStatedAndDegradesTheAnalysis() {
        FakeUpstreams.FULFILLMENTS.put(fulfillmentId, Fixtures.status(Fetch.Status.TIMEOUT));
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("DEGRADED", a.getString("status"));
        assertTrue(a.getList("unknowns", String.class).stream().anyMatch(u -> u.contains("FULFILLMENT: TIMEOUT")));
        assertTrue(a.getList("findings", Map.class).stream()
            .noneMatch(f -> f.get("statement").toString().startsWith("FULFILLMENT")));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void orderServiceDownMeansNoDiagnosisAndNoModelCall() {
        FakeUpstreams.ORDERS.put(orderId, Fixtures.status(Fetch.Status.UNREACHABLE));
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("DEGRADED", a.getString("status"));
        assertTrue(a.getString("statusDetail").contains("no analysis was attempted"));
        assertTrue(a.getList("findings").isEmpty());
        assertNull(a.getString("summary"));
        assertTrue(ScriptedModel.REQUESTS.isEmpty());
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void modelDowntimeIsTypedAndInventsNothing() {
        ScriptedModel.script = r -> ModelResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint HTTP 503");
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("MODEL_UNAVAILABLE", a.getString("status"));
        assertNull(a.getString("summary"));
        assertTrue(a.getList("findings").isEmpty());
        assertTrue(a.getList("suggestedActions").isEmpty());
        assertFalse(a.getList("evidence").isEmpty(), "evidence is still returned");

        ScriptedModel.script = r -> ModelResult.failure(ModelResult.Outcome.TIMEOUT, "model did not answer");
        assertEquals("MODEL_UNAVAILABLE", Api.await(Api.submit(orderId)).getString("status"));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void invalidOrTruncatedOutputIsRejected() {
        ScriptedModel.script = r -> ScriptedModel.content("The ERP is slow. {not json");
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("MODEL_INVALID_OUTPUT", a.getString("status"));
        assertTrue(a.getList("findings").isEmpty());
        assertNull(a.getString("summary"));

        ScriptedModel.script = r -> new ModelResult(ModelResult.Outcome.INVALID, null, "length", 900, 4096,
            "model output was truncated at the token limit");
        JsonPath b = Api.await(Api.submit(orderId));
        assertEquals("MODEL_INVALID_OUTPUT", b.getString("status"));
        assertTrue(b.getString("statusDetail").contains("truncated"));

        ScriptedModel.script = r -> ScriptedModel.content("{\"summary\":\"x\",\"findings\":[]}");
        assertEquals("MODEL_INVALID_OUTPUT", Api.await(Api.submit(orderId)).getString("status"));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void uncitedFindingsAndUnlistedActionsAreDropped() {
        ScriptedModel.script = r -> ScriptedModel.content("""
            {"summary":"ERP slow","findings":[
              {"statement":"ERP acknowledgement is pending","evidenceRefs":["ev_4"],"confidence":"HIGH"},
              {"statement":"The database is corrupt","evidenceRefs":["ev_99"],"confidence":"HIGH"},
              {"statement":"Nothing cited","evidenceRefs":[],"confidence":"LOW"}],
             "unknowns":[],
             "suggestedActions":[
              {"type":"RUN_SHELL","description":"rm -rf","expectedEffect":"x","rollback":"x"},
              {"type":"ROLLBACK_DEPLOYMENT_PROPOSAL","description":"Roll back fulfillment-adapter",
               "expectedEffect":"Earlier revision","rollback":"Re-promote current digest"}]}""");
        JsonPath a = Api.await(Api.submit(orderId));
        assertEquals("COMPLETED", a.getString("status"));
        assertEquals(1, a.getList("findings").size());
        assertEquals(2, a.getInt("quality.unsupportedAssertions"));
        assertEquals(1, a.getInt("quality.droppedActions"));
        assertEquals("ROLLBACK_DEPLOYMENT_PROPOSAL", a.getString("suggestedActions[0].type"));
        assertEquals(false, a.getBoolean("suggestedActions[0].executable"));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void theQueueIsBoundedAndExcessRequestsAreBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ScriptedModel.script = r -> {
            started.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ScriptedModel.echo(r);
        };
        List<String> accepted = new ArrayList<>();
        try {
            accepted.add(Api.submit(orderId));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) {
                accepted.add(Api.submit(orderId));
            }
            given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(429)
                .header("Retry-After", "30").body("code", is("BUSY"));
        } finally {
            release.countDown();
        }
        for (String id : accepted) {
            assertEquals("COMPLETED", Api.await(id).getString("status"));
        }
    }

    @Test
    void theApiHasNoWriteRoutes() {
        JsonPath api = given().accept("application/json").get("/q/openapi?format=json").then().statusCode(200)
            .extract().jsonPath();
        Map<String, Map<String, Object>> paths = api.getMap("paths");
        paths.forEach((path, ops) -> {
            for (String verb : List.of("put", "patch", "delete")) {
                assertFalse(ops.containsKey(verb), verb + " " + path);
            }
            if (path.startsWith("/api/v1/ai")) {
                assertTrue(Set.of("/api/v1/ai/incidents/{incidentId}/analyze", "/api/v1/ai/analyses/{analysisId}")
                    .contains(path), path);
            }
        });
    }
}
