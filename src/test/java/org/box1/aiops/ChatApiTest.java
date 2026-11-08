package org.box1.aiops;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.box1.aiops.model.ChatMessage;
import org.box1.aiops.model.ChatRequest;
import org.box1.aiops.model.ChatResult;
import org.box1.aiops.model.ModelResult;
import org.box1.aiops.support.FakeUpstreams;
import org.box1.aiops.support.Fixtures;
import org.box1.aiops.support.ScriptedModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;

/**
 * The Assistant's chat surface (D-016): a bounded read-only tool loop under the caller's own roles, typed failure
 * states, per-person conversations, the shared model queue, and audit logs without content.
 */
@QuarkusTest
@TestSecurity(user = "otto", roles = "aiops-viewer")
@OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
class ChatApiTest {
    private static final String SECRET_QUESTION = "what-is-the-secret-question-7731";
    private static final String SECRET_ANSWER = "the-secret-answer-5519";

    String orderId;
    final List<String> logs = new CopyOnWriteArrayList<>();
    final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            String m = r instanceof org.jboss.logmanager.ExtLogRecord ext ? ext.getFormattedMessage() : r.getMessage();
            logs.add(String.valueOf(m));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void setUp() {
        ScriptedModel.reset();
        FakeUpstreams.CALLS.clear();
        orderId = Fixtures.newOrderId();
        Fixtures.erpSlowdown(orderId, "ful_" + orderId.substring(4), "res_" + orderId.substring(4));
        java.util.logging.Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        java.util.logging.Logger.getLogger("").removeHandler(capture);
    }

    static String create() {
        return given().post("/api/v1/ai/conversations").then().statusCode(201).extract().path("conversationId");
    }

    static void send(String conversationId, String text) {
        given().contentType("application/json").body(Map.of("text", text))
            .post("/api/v1/ai/conversations/" + conversationId + "/messages").then().statusCode(202)
            .body("status", is("QUEUED"));
    }

    /** The last turn once it is finished. */
    static JsonPath await(String conversationId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            JsonPath c = given().get("/api/v1/ai/conversations/" + conversationId).then().statusCode(200).extract()
                .jsonPath();
            String status = c.getString("turns[-1].status");
            if (status != null && !status.equals("QUEUED") && !status.equals("RUNNING")) {
                return c;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("turn still " + status);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** The model reads one tool, then answers from it. */
    private void readThenAnswer(String tool, String args, String answer) {
        AtomicInteger calls = new AtomicInteger();
        ScriptedModel.chatScript = r -> calls.getAndIncrement() == 0
            ? ScriptedModel.tools(ScriptedModel.call("c1", tool, args))
            : ScriptedModel.text(answer);
    }

    @Test
    void aQuestionIsAnsweredFromAToolRead() {
        readThenAnswer("get_order_timeline", "{\"orderId\":\"" + orderId + "\"}", "Order " + orderId + " is waiting on the ERP.");
        String id = create();
        send(id, "What is happening with " + orderId + "?");
        JsonPath c = await(id);
        assertEquals("COMPLETED", c.getString("turns[0].status"));
        assertEquals("Order " + orderId + " is waiting on the ERP.", c.getString("turns[0].answer"));
        assertEquals("get_order_timeline", c.getString("turns[0].steps[0].tool"));
        assertEquals(orderId, c.getString("turns[0].steps[0].target"));
        assertEquals("ok", c.getString("turns[0].steps[0].decision"));
        assertEquals("OK", c.getString("turns[0].steps[0].retrievalStatus"));
        assertEquals(2, c.getInt("turns[0].model.modelCalls"));
        assertEquals(List.of("timeline:" + orderId), FakeUpstreams.CALLS);
        // The model saw the policy, the question, and the tool result as untrusted data.
        ChatRequest second = ScriptedModel.CHAT_REQUESTS.get(1);
        assertEquals("system", second.messages().get(0).role());
        ChatMessage toolResult = second.messages().get(second.messages().size() - 1);
        assertEquals("tool", toolResult.role());
        assertEquals("c1", toolResult.toolCallId());
        assertTrue(toolResult.content().startsWith("{\"untrusted\":true"), toolResult.content());
        assertFalse(ScriptedModel.CHAT_REQUESTS.get(0).tools().stream().anyMatch(t -> t.name().equals("analyze_incident")));
    }

    @Test
    void earlierAnswersAreHistoryButEarlierToolResultsAreNot() {
        readThenAnswer("get_order_timeline", "{\"orderId\":\"" + orderId + "\"}", "first answer");
        String id = create();
        send(id, "first question");
        await(id);
        ScriptedModel.CHAT_REQUESTS.clear();
        ScriptedModel.chatScript = r -> ScriptedModel.text("second answer");
        send(id, "second question");
        JsonPath c = await(id);
        assertEquals("second answer", c.getString("turns[1].answer"));
        List<ChatMessage> m = ScriptedModel.CHAT_REQUESTS.get(0).messages();
        assertEquals(List.of("system", "user", "assistant", "user"), m.stream().map(ChatMessage::role).toList());
        assertEquals("first answer", m.get(2).content());
        assertEquals("second question", m.get(3).content());
    }

    @Test
    void unknownToolsAndInvalidArgumentsReadNothingAndAreFedBackAsErrors() {
        AtomicInteger n = new AtomicInteger();
        ScriptedModel.chatScript = r -> switch (n.getAndIncrement()) {
            case 0 -> ScriptedModel.tools(
                ScriptedModel.call("a", "cancel_order", "{\"orderId\":\"" + orderId + "\"}"),
                ScriptedModel.call("b", "get_order_timeline", "{\"orderId\":\"../../admin\"}"),
                ScriptedModel.call("c", "get_deployed_revision", "{\"service\":\"kube-apiserver\"}"),
                ScriptedModel.call("d", "get_order_timeline", "not json"));
            default -> ScriptedModel.text("I could not read that.");
        };
        String id = create();
        send(id, "cancel " + orderId);
        JsonPath c = await(id);
        assertEquals("COMPLETED", c.getString("turns[0].status"));
        assertEquals(List.of("unknown_tool", "invalid_argument", "invalid_argument", "invalid_argument"),
            c.getList("turns[0].steps.decision"));
        assertEquals(List.of("unknown", "get_order_timeline", "get_deployed_revision", "get_order_timeline"),
            c.getList("turns[0].steps.tool"));
        assertTrue(FakeUpstreams.CALLS.isEmpty(), "no upstream read: " + FakeUpstreams.CALLS);
        List<ChatMessage> fed = ScriptedModel.CHAT_REQUESTS.get(1).messages();
        assertTrue(fed.stream().filter(m -> m.role().equals("tool")).allMatch(m -> m.content().startsWith("error:")));
    }

    @Test
    void theToolBudgetEndsInAFinalAnswerWithoutTools() {
        // A model that keeps asking for reads (as injected text might try to make it) is stopped by the budget.
        ScriptedModel.chatScript = r -> r.tools().isEmpty() ? ScriptedModel.text("Here is what I found so far.")
            : ScriptedModel.tools(
                ScriptedModel.call("x1", "get_order_timeline", "{\"orderId\":\"" + orderId + "\"}"),
                ScriptedModel.call("x2", "get_fulfillment_status", "{\"orderId\":\"" + orderId + "\"}"),
                ScriptedModel.call("x3", "get_deployed_revision", "{\"service\":\"order-service\"}"));
        String id = create();
        send(id, "tell me everything");
        JsonPath c = await(id);
        assertEquals("LIMIT_REACHED", c.getString("turns[0].status"));
        assertEquals("Here is what I found so far.", c.getString("turns[0].answer"));
        assertEquals(8, c.getInt("turns[0].model.toolCalls"));
        assertTrue(c.getList("turns[0].steps.decision").contains("limit"));
        ChatRequest last = ScriptedModel.CHAT_REQUESTS.get(ScriptedModel.CHAT_REQUESTS.size() - 1);
        assertTrue(last.tools().isEmpty(), "the final call offers no tools");
        assertTrue(ScriptedModel.CHAT_REQUESTS.size() <= 5, "at most 4 rounds plus the final call");
    }

    @Test
    void modelDowntimeGivesNoAnswer() {
        ScriptedModel.chatScript = r -> ChatResult.failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint unreachable");
        String id = create();
        send(id, "anything");
        JsonPath c = await(id);
        assertEquals("MODEL_UNAVAILABLE", c.getString("turns[0].status"));
        assertEquals(null, c.getString("turns[0].answer"));
    }

    @Test
    void truncatedOutputIsInvalidAndNotShown() {
        ScriptedModel.chatScript = r -> new ChatResult(ModelResult.Outcome.INVALID, null, List.of(), "length", 10, 10,
            "model output was truncated at the token limit");
        String id = create();
        send(id, "anything");
        JsonPath c = await(id);
        assertEquals("MODEL_INVALID_OUTPUT", c.getString("turns[0].status"));
        assertEquals(null, c.getString("turns[0].answer"));
    }

    @Test
    void aSecondMessageWhileOneIsRunningIsRefused() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ScriptedModel.chatScript = r -> {
            started.countDown();
            await(release);
            return ScriptedModel.text("done");
        };
        String id = create();
        try {
            send(id, "first");
            assertTrue(started.await(10, TimeUnit.SECONDS));
            given().contentType("application/json").body(Map.of("text", "second"))
                .post("/api/v1/ai/conversations/" + id + "/messages").then().statusCode(409)
                .body("code", is("TURN_IN_PROGRESS"));
        } finally {
            release.countDown();
        }
        assertEquals("COMPLETED", await(id).getString("turns[0].status"));
    }

    @Test
    @TestSecurity(user = "otto", roles = "aiops-analyst")
    @OidcSecurity(claims = @Claim(key = "azp", value = "agentic-operations-cli"))
    void chatSharesTheModelQueueWithAnalyses() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ScriptedModel.chatScript = r -> {
            started.countDown();
            await(release);
            return ScriptedModel.text("done");
        };
        List<String> ids = new ArrayList<>();
        try {
            String first = create();
            ids.add(first);
            send(first, "hold the model");
            assertTrue(started.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) {
                given().post("/api/v1/ai/incidents/inc_" + orderId + "/analyze").then().statusCode(202);
            }
            String busy = create();
            given().contentType("application/json").body(Map.of("text", "one more"))
                .post("/api/v1/ai/conversations/" + busy + "/messages").then().statusCode(429)
                .header("Retry-After", "30").body("code", is("BUSY"));
            given().get("/api/v1/ai/conversations/" + busy).then().statusCode(200).body("turns.size()", is(0));
        } finally {
            release.countDown();
        }
        assertEquals("COMPLETED", await(ids.get(0)).getString("turns[0].status"));
    }

    @Test
    void messagesAreBounded() {
        String id = create();
        given().contentType("application/json").body(Map.of("text", " "))
            .post("/api/v1/ai/conversations/" + id + "/messages").then().statusCode(400).body("code", is("INVALID_MESSAGE"));
        given().contentType("application/json").body(Map.of("text", "x".repeat(2001)))
            .post("/api/v1/ai/conversations/" + id + "/messages").then().statusCode(400).body("code", is("INVALID_MESSAGE"));
        given().get("/api/v1/ai/conversations/conv_nothex").then().statusCode(400);
        given().get("/api/v1/ai/conversations/conv_0123456789abcdef0123456789abcdef").then().statusCode(404)
            .body("code", is("CONVERSATION_NOT_FOUND"));
        assertTrue(ScriptedModel.CHAT_REQUESTS.isEmpty());
    }

    @Test
    void auditLogsCarryNoQuestionOrAnswer() {
        readThenAnswer("get_order_timeline", "{\"orderId\":\"" + orderId + "\"}", SECRET_ANSWER);
        String id = create();
        send(id, SECRET_QUESTION);
        assertEquals(SECRET_ANSWER, await(id).getString("turns[0].answer"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("chat turn finished") && l.contains(id)), String.valueOf(logs));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("chat_tool") && l.contains("tool=get_order_timeline")));
        assertFalse(logs.stream().anyMatch(l -> l.contains(SECRET_QUESTION) || l.contains(SECRET_ANSWER)), String.valueOf(logs));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
