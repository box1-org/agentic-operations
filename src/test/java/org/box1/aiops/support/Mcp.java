package org.box1.aiops.support;

import static io.restassured.RestAssured.given;

import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/** Minimal Streamable HTTP MCP client for tests. */
public final class Mcp {
    private static final String ACCEPT = "application/json, text/event-stream";
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private final String session;

    private Mcp(String session) {
        this.session = session;
    }

    public static Response initializeRaw() {
        return given().contentType(ContentType.JSON).accept(ACCEPT).body("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"aiops-test","version":"1"}}}""").post("/mcp");
    }

    public static Mcp connect() {
        String session = initializeRaw().then().statusCode(200).extract().header("Mcp-Session-Id");
        given().contentType(ContentType.JSON).accept(ACCEPT).header("Mcp-Session-Id", session)
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").post("/mcp").then()
            .statusCode(202);
        return new Mcp(session);
    }

    public JsonPath request(String method, ObjectNode params) {
        ObjectNode body = Fixtures.JSON.createObjectNode();
        body.put("jsonrpc", "2.0").put("id", IDS.incrementAndGet()).put("method", method);
        if (params != null) {
            body.set("params", params);
        }
        return given().contentType(ContentType.JSON).accept(ACCEPT).header("Mcp-Session-Id", session)
            .body(body.toString()).post("/mcp").then().statusCode(200).extract().jsonPath();
    }

    /** Calls a tool; returns the JSON-RPC response. */
    public JsonPath call(String tool, ObjectNode arguments) {
        ObjectNode params = Fixtures.JSON.createObjectNode().put("name", tool);
        params.set("arguments", arguments == null ? Fixtures.JSON.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    public static ObjectNode args(String... keyValues) {
        ObjectNode a = Fixtures.JSON.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            a.put(keyValues[i], keyValues[i + 1]);
        }
        return a;
    }

    /** The untrusted wrapper's {@code result}, for a successful call. */
    public static JsonPath result(JsonPath response) {
        if (response.getBoolean("result.isError")) {
            throw new AssertionError("tool error: " + response.getString("result.content[0].text"));
        }
        JsonPath wrapper = new JsonPath(response.getString("result.content[0].text"));
        if (!wrapper.getBoolean("untrusted")) {
            throw new AssertionError("result is not labelled untrusted");
        }
        return wrapper.setRootPath("result");
    }
}
