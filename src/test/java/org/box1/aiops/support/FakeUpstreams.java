package org.box1.aiops.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.box1.aiops.evidence.Fetch;
import org.box1.aiops.evidence.Upstreams;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.Mock;

/** Programmable upstreams. Unknown keys read as NOT_FOUND. Every call is recorded as "method:id". */
@Mock
@ApplicationScoped
public class FakeUpstreams implements Upstreams {
    public static final Map<String, Fetch> ORDERS = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> TIMELINES = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> FULFILLMENTS = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> RESERVATIONS = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> INFOS = new ConcurrentHashMap<>();
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();
    /** Platform reads (box1-docs#111): unset means "not configured", as on a service without platform access. */
    public static final Map<String, Fetch> TRACES = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> LOGS = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> ALERTS = new ConcurrentHashMap<>();
    public static final Map<String, Fetch> TARGETS = new ConcurrentHashMap<>();
    public static volatile boolean platformConfigured;

    public static void resetPlatform() {
        TRACES.clear();
        LOGS.clear();
        ALERTS.clear();
        TARGETS.clear();
        platformConfigured = false;
    }

    public static void ok(Map<String, Fetch> map, String key, JsonNode body) {
        map.put(key, Fetch.ok(body));
    }

    @Override
    public Fetch order(String orderId) {
        return read("order", orderId, ORDERS);
    }

    @Override
    public Fetch timeline(String orderId, int limit) {
        return read("timeline", orderId, TIMELINES);
    }

    @Override
    public Fetch fulfillment(String fulfillmentRequestId) {
        return read("fulfillment", fulfillmentRequestId, FULFILLMENTS);
    }

    @Override
    public Fetch reservation(String reservationId) {
        return read("reservation", reservationId, RESERVATIONS);
    }

    @Override
    public Fetch info(String service) {
        return read("info", service, INFOS);
    }

    @Override
    public Fetch trace(String traceId) {
        return platform("trace", traceId, TRACES);
    }

    @Override
    public Fetch logs(List<String> namespaces, String field, String value, java.time.Instant from,
            java.time.Instant to, int limit) {
        return platform("logs", field + "=" + value + "@" + String.join("|", namespaces), LOGS, value);
    }

    @Override
    public Fetch alerts(String namespace) {
        return platform("alerts", namespace, ALERTS);
    }

    @Override
    public Fetch targets(String namespace) {
        return platform("targets", namespace, TARGETS);
    }

    private static Fetch platform(String method, String id, Map<String, Fetch> map) {
        return platform(method, id, map, id);
    }

    private static Fetch platform(String method, String id, Map<String, Fetch> map, String key) {
        if (!platformConfigured) {
            // Like the real client: no request leaves the service when the source is not configured.
            return Fetch.of(Fetch.Status.UNAVAILABLE, method + " is not configured for this service");
        }
        CALLS.add(method + ":" + id);
        return map.getOrDefault(key, Fetch.of(Fetch.Status.NOT_FOUND, "not found"));
    }

    private static Fetch read(String method, String id, Map<String, Fetch> map) {
        CALLS.add(method + ":" + id);
        return map.getOrDefault(id, Fetch.of(Fetch.Status.NOT_FOUND, "not found"));
    }
}
