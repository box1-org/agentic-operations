package org.box1.aiops.tracing;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.box1.aiops.support.Fixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;

/**
 * Runs one analysis and a two-message Assistant conversation through the REST API against a real vLLM endpoint, with
 * the OpenTelemetry SDK on and the Langfuse export configured, then flushes the spans. The traces are then checked in
 * Langfuse (trace names {@code analyze-incident} and {@code answer-message}, environment {@code test}). Opt-in: set
 * {@code AIOPS_LIVE_MODEL_URL}, {@code AIOPS_LIVE_LANGFUSE=true}, {@code LANGFUSE_PUBLIC_KEY} and
 * {@code LANGFUSE_SECRET_KEY} (and {@code LANGFUSE_BASE_URL} if not US cloud). Upstream services are the test fakes.
 */
@QuarkusTest
@TestProfile(LiveLangfuseTest.Live.class)
@EnabledIfEnvironmentVariable(named = "AIOPS_LIVE_LANGFUSE", matches = "true")
@TestSecurity(user = "otto", roles = { "aiops-viewer", "aiops-analyst" })
@OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
class LiveLangfuseTest {
    static final Set<String> TERMINAL = Set.of("COMPLETED", "DEGRADED", "MODEL_UNAVAILABLE", "MODEL_INVALID_OUTPUT",
        "FAILED", "LIMIT_REACHED");

    /** OpenTelemetry on, the real model instead of the scripted one, and the collector export pointed nowhere. */
    public static class Live implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.otel.sdk.disabled", "false",
                "quarkus.otel.exporter.otlp.traces.endpoint", "http://127.0.0.1:9",
                "quarkus.arc.exclude-types", "org.box1.aiops.support.ScriptedModel",
                "aiops.model.base-url", System.getenv().getOrDefault("AIOPS_LIVE_MODEL_URL", ""),
                "aiops.model.timeout", "120s");
        }
    }

    @Inject
    OpenTelemetry otel;

    @AfterEach
    void flush() {
        ((OpenTelemetrySdk) otel).getSdkTracerProvider().forceFlush().join(30, TimeUnit.SECONDS);
    }

    static JsonPath await(String path, String statusPath) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (true) {
            JsonPath body = given().get(path).then().statusCode(200).extract().jsonPath();
            String status = body.getString(statusPath);
            if (TERMINAL.contains(status)) {
                return body;
            }
            assertTrue(System.currentTimeMillis() < deadline, path + " still " + status);
            Thread.sleep(250);
        }
    }

    @Test
    void analysisTrace() throws Exception {
        String orderId = Fixtures.newOrderId();
        Fixtures.erpSlowdown(orderId, "ful_" + orderId.substring(4), "res_" + orderId.substring(4));
        String id = given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(202).extract()
            .path("analysisId");
        JsonPath done = await("/api/v1/ai/analyses/" + id, "status");
        System.out.println("LIVE analysis " + id + " order " + orderId + " status " + done.getString("status"));
        assertEquals("COMPLETED", done.getString("status"), done.prettify());
    }

    @Test
    void conversationTraces() throws Exception {
        String orderId = Fixtures.newOrderId();
        Fixtures.erpSlowdown(orderId, "ful_" + orderId.substring(4), "res_" + orderId.substring(4));
        String conv = given().post("/api/v1/ai/conversations").then().statusCode(201).extract().path("conversationId");
        for (String q : new String[] {"Why is order " + orderId + " not shipped yet?",
            "Has the ERP acknowledged it since?"}) {
            given().contentType("application/json").body(Map.of("text", q))
                .post("/api/v1/ai/conversations/" + conv + "/messages").then().statusCode(202);
            JsonPath c = await("/api/v1/ai/conversations/" + conv, "turns[-1].status");
            System.out.println("LIVE conversation " + conv + " turn " + c.getString("turns[-1].turnId") + " status "
                + c.getString("turns[-1].status") + " tools " + c.getList("turns[-1].steps.tool"));
            assertTrue(Set.of("COMPLETED", "LIMIT_REACHED").contains(c.getString("turns[-1].status")), c.prettify());
        }
    }
}
