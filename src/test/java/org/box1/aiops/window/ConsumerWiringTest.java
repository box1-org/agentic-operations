package org.box1.aiops.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import org.box1.aiops.support.Fixtures;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;

import static io.restassured.RestAssured.given;

/** The evidence-events channel feeds the window; poison is skipped and counted; Kafka is not part of readiness. */
@QuarkusTest
class ConsumerWiringTest {
    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    EvidenceWindow window;

    @Inject
    MeterRegistry registry;

    @Test
    void recordsFlowThroughTheChannelAndPoisonIsCounted() {
        String orderId = Fixtures.newOrderId();
        ObjectNode payload = Fixtures.JSON.createObjectNode().put("orderId", orderId).put("toState", "SHIPPED");
        var source = connector.source("evidence-events");
        byte[] event = Fixtures.event("order.state-changed.v1", orderId, payload);
        source.send(event);
        source.send("{garbage".getBytes(StandardCharsets.UTF_8));
        source.send(event);
        assertEquals(1, window.forKey(orderId, 10).size());
        double skipped = registry.find("aiops_kafka_records_total").tag("reason", "unparseable").counters().stream()
            .mapToDouble(c -> c.count()).sum();
        assertTrue(skipped >= 1);
    }

    @Test
    void readinessDoesNotIncludeKafkaOrTheModel() {
        String body = given().get("/q/health/ready").then().statusCode(200).extract().asString();
        assertTrue(!body.toLowerCase().contains("kafka") && !body.toLowerCase().contains("vllm"), body);
    }
}
