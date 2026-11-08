package org.box1.aiops.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.box1.aiops.AiopsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.api.OpenTelemetry;

/** Platform reads (box1-docs#111): bearer from the projected token, rotation, refusals, size and query building. */
class HttpUpstreamsPlatformTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @TempDir
    Path dir;
    HttpServer server;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> body = new AtomicReference<>("{}");
    Path token;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(URLDecoder.decode(exchange.getRequestURI().getRawPath() + "?"
                + exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
        token = dir.resolve("token");
        Files.writeString(token, "first.token-value");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpUpstreams upstreams(boolean configured, int maxBytes) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> platform = Map.of(
            "tokenPath", configured ? Optional.of(token.toString()) : Optional.empty(),
            "caPath", Optional.empty(),
            "tempoUrl", Optional.of(base + "/tempo"),
            "lokiUrl", Optional.of(base + "/loki"),
            "thanosTenancyUrl", Optional.of(base + "/thanos"),
            "alertmanagerTenancyUrl", Optional.of(base + "/am"),
            "orderNamespaces", List.of("order-service-dev"),
            "maxResponseBytes", maxBytes,
            "readTimeout", Duration.ofSeconds(2),
            "logLimit", 50);
        Map<String, Object> upstream = Map.of("orderUrl", base, "inventoryUrl", base, "fulfillmentUrl", base,
            "connectTimeout", Duration.ofSeconds(1), "readTimeout", Duration.ofSeconds(2));
        Map<String, Object> evidence = Map.of("sourceMaxBytes", 16384, "timelineLimit", 50, "telemetryEnabled", false);
        AiopsConfig config = proxy(AiopsConfig.class, Map.of(
            "platform", proxy(AiopsConfig.Platform.class, platform),
            "upstream", proxy(AiopsConfig.Upstream.class, upstream),
            "evidence", proxy(AiopsConfig.Evidence.class, evidence)));
        return new HttpUpstreams(config, JSON, OpenTelemetry.noop());
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (!values.containsKey(m.getName())) {
                throw new UnsupportedOperationException(m.getName());
            }
            return values.get(m.getName());
        });
    }

    @Test
    void readsSendTheProjectedTokenAndPickUpARotation() throws IOException {
        HttpUpstreams u = upstreams(true, 65536);
        assertTrue(u.trace(TRACE).isOk());
        assertEquals("Bearer first.token-value", auth.get(0));
        assertEquals("/tempo/api/traces/" + TRACE + "?null", paths.get(0));
        Files.writeString(token, "second.token-value");
        Files.setLastModifiedTime(token, FileTime.from(Instant.now().plusSeconds(5)));
        assertTrue(u.trace(TRACE).isOk());
        assertEquals("Bearer second.token-value", auth.get(1));
    }

    @Test
    void anUnconfiguredSourceSendsNothing() {
        Fetch f = upstreams(false, 65536).trace(TRACE);
        assertEquals(Fetch.Status.UNAVAILABLE, f.status());
        assertTrue(f.detail().contains("not configured"));
        assertTrue(paths.isEmpty());
    }

    @Test
    void refusalsOversizeAndBadJsonAreTyped() {
        HttpUpstreams u = upstreams(true, 64);
        status.set(403);
        assertEquals(Fetch.Status.UNAUTHORIZED, u.alerts("order-service-dev").status());
        status.set(200);
        body.set("{\"data\":\"" + "x".repeat(200) + "\"}");
        assertEquals(Fetch.Status.ERROR, u.alerts("order-service-dev").status());
        body.set("not json");
        assertEquals(Fetch.Status.ERROR, u.alerts("order-service-dev").status());
    }

    @Test
    void queriesAreBuiltOnlyFromValidatedValues() {
        HttpUpstreams u = upstreams(true, 65536);
        u.logs(List.of("order-service-dev", "inventory-service-dev"), "orderId", "ord_abc", Instant.ofEpochSecond(10),
            Instant.ofEpochSecond(20), 50);
        assertEquals("/loki/loki/api/v1/query_range?query={kubernetes_namespace_name=~\"order-service-dev|"
            + "inventory-service-dev\"} | json | structured_mdc_orderId=\"ord_abc\"&start=10000000000&end=20000000000"
            + "&limit=50&direction=backward", paths.get(0));
        u.alerts("fulfillment-adapter-dev");
        assertEquals("/am/api/v2/alerts?namespace=fulfillment-adapter-dev&active=true&silenced=false&inhibited=false",
            paths.get(1));
        u.targets("order-service-dev");
        assertEquals("/thanos/api/v1/query?namespace=order-service-dev&query=up", paths.get(2));
        paths.clear();
        assertEquals(Fetch.Status.ERROR, u.logs(List.of("ns\"} or {x"), "orderId", "ord_a", Instant.EPOCH,
            Instant.EPOCH, 1).status());
        assertEquals(Fetch.Status.ERROR, u.logs(List.of("order-service-dev"), "password", "x", Instant.EPOCH,
            Instant.EPOCH, 1).status());
        assertEquals(Fetch.Status.ERROR, u.logs(List.of("order-service-dev"), "orderId", "ord_\"x", Instant.EPOCH,
            Instant.EPOCH, 1).status());
        assertEquals(Fetch.Status.ERROR, u.alerts("../../admin").status());
        assertEquals(Fetch.Status.ERROR, u.trace("ZZZ").status());
        assertTrue(paths.isEmpty(), paths.toString());
    }

    @Test
    void shapesKeepOnlyBoundedNamedFields() throws IOException {
        var logs = PlatformShapes.logs(JSON.readTree("""
            {"data":{"result":[{"stream":{"kubernetes_namespace_name":"order-service-dev","secret":"no"},
              "values":[["2000000000","{\\"level\\":\\"INFO\\",\\"message\\":\\"%s\\",\\"password\\":\\"p\\"}"],
                        ["1000000000","plain text line"]]}]}}""".formatted("m".repeat(400))), JSON, 50);
        assertEquals(2, logs.size());
        assertEquals("plain text line", logs.get(0).path("message").asText(), "oldest first");
        assertEquals(259, logs.get(1).path("message").asText().length());
        assertFalse(logs.toString().contains("password"));
        assertFalse(logs.toString().contains("secret"));
        var targets = PlatformShapes.targets("order-service-dev", JSON.readTree("""
            {"data":{"result":[{"metric":{"job":"a","pod":"p"},"value":[1,"0"]},{"metric":{"job":"b"},"value":[1,"1"]}]}}"""));
        assertEquals(2, targets.path("targets").asInt());
        assertEquals("a", targets.path("down").get(0).path("job").asText());
        var trace = PlatformShapes.trace(TRACE, JSON.readTree("{}"));
        assertEquals(0, trace.path("spanCount").asInt());
        assertNull(trace.get("rootSpan"));
    }
}
