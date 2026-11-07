package org.box1.aiops.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class McpCompatTest {
    private static final String ACCEPT = "application/json, text/event-stream";

    @Test
    void initializeListAndCallOverStreamableHttp() {
        String session = given().contentType(ContentType.JSON).accept(ACCEPT)
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"compat-test","version":"1"}}}""")
            .post("/mcp").then().statusCode(200)
            .header("Mcp-Session-Id", notNullValue()).extract().header("Mcp-Session-Id");
        given().contentType(ContentType.JSON).accept(ACCEPT).header("Mcp-Session-Id", session)
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
            .post("/mcp").then().statusCode(202);
        given().contentType(ContentType.JSON).accept(ACCEPT).header("Mcp-Session-Id", session)
            .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
            .post("/mcp").then().statusCode(200).body("result.tools.name", hasItem("serviceName"));
        given().contentType(ContentType.JSON).accept(ACCEPT).header("Mcp-Session-Id", session)
            .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"serviceName\",\"arguments\":{}}}")
            .post("/mcp").then().statusCode(200).body("result.content[0].text", org.hamcrest.Matchers.is("agentic-operations"));
    }
}
