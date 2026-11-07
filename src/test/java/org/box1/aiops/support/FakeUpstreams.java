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

    private static Fetch read(String method, String id, Map<String, Fetch> map) {
        CALLS.add(method + ":" + id);
        return map.getOrDefault(id, Fetch.of(Fetch.Status.NOT_FOUND, "not found"));
    }
}
