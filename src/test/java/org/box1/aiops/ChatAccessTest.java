package org.box1.aiops;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.box1.aiops.support.FakeUpstreams;
import org.box1.aiops.support.ScriptedModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;

/** Who may chat (D-016): viewers and analysts from accepted clients, each only in their own conversations. */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatAccessTest {
    static String ottosConversation;

    @BeforeEach
    void setUp() {
        ScriptedModel.reset();
        FakeUpstreams.CALLS.clear();
    }

    @Test
    @Order(1)
    @TestSecurity(user = "otto", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void aViewerFromTheConsoleCanChat() {
        ottosConversation = given().post("/api/v1/ai/conversations").then().statusCode(201).extract()
            .path("conversationId");
        given().contentType("application/json").body(Map.of("text", "hello"))
            .post("/api/v1/ai/conversations/" + ottosConversation + "/messages").then().statusCode(202);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "vera", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void someoneElsesConversationDoesNotExistForYou() {
        given().get("/api/v1/ai/conversations/" + ottosConversation).then().statusCode(404)
            .body("code", is("CONVERSATION_NOT_FOUND"));
        given().contentType("application/json").body(Map.of("text", "what did otto ask?"))
            .post("/api/v1/ai/conversations/" + ottosConversation + "/messages").then().statusCode(404);
    }

    @Test
    @Order(3)
    void anonymousCallersAreRejected() {
        given().post("/api/v1/ai/conversations").then().statusCode(401);
        given().get("/api/v1/ai/conversations/" + ottosConversation).then().statusCode(401);
    }

    @Test
    @Order(4)
    @TestSecurity(user = "nora", roles = "telemetry-reader")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void callersWithoutAnAiopsRoleAreRejected() {
        given().post("/api/v1/ai/conversations").then().statusCode(403);
    }

    @Test
    @Order(5)
    @TestSecurity(user = "eve", roles = "aiops-viewer")
    @OidcSecurity(claims = @Claim(key = "azp", value = "some-other-client"))
    void unacceptedClientsAreRejected() {
        given().post("/api/v1/ai/conversations").then().statusCode(403).body("code", is("FORBIDDEN"));
        assertTrue(ScriptedModel.CHAT_REQUESTS.isEmpty());
        assertTrue(FakeUpstreams.CALLS.isEmpty());
    }
}
