package org.box1.aiops.support;

import static io.restassured.RestAssured.given;

import io.restassured.path.json.JsonPath;

/** REST helpers: submit and wait for a terminal status. */
public final class Api {
    private static final java.util.Set<String> TERMINAL = java.util.Set.of("COMPLETED", "DEGRADED",
        "MODEL_UNAVAILABLE", "MODEL_INVALID_OUTPUT", "FAILED");

    private Api() {
    }

    public static String submit(String orderId) {
        return given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(202).extract()
            .path("analysisId");
    }

    public static JsonPath await(String analysisId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            JsonPath body = given().get("/api/v1/ai/analyses/" + analysisId).then().statusCode(200).extract()
                .jsonPath();
            if (TERMINAL.contains(body.getString("status"))) {
                return body;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("analysis " + analysisId + " still " + body.getString("status"));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
