package org.box1.aiops.evidence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.BoundedHttp;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.quarkus.logging.Log;

/** Plain GETs with bounded time and size. Paths are built only from validated IDs (see {@link org.box1.aiops.Ids}). */
@ApplicationScoped
public class HttpUpstreams implements Upstreams {
    private final HttpClient http;
    private final ObjectMapper json;
    private final AiopsConfig.Upstream cfg;
    private final int maxBytes;
    private final Map<String, String> infoBases;

    private final OpenTelemetry otel;

    @Inject
    public HttpUpstreams(AiopsConfig config, ObjectMapper json, OpenTelemetry otel) {
        this.otel = otel;
        this.cfg = config.upstream();
        this.json = json;
        this.maxBytes = config.evidence().sourceMaxBytes();
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(cfg.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
        this.infoBases = Map.of("order-service", cfg.orderUrl(), "inventory-service", cfg.inventoryUrl(),
            "fulfillment-adapter", cfg.fulfillmentUrl());
    }

    @Override
    public Fetch order(String orderId) {
        return get(cfg.orderUrl(), "/api/v1/orders/" + orderId);
    }

    @Override
    public Fetch timeline(String orderId, int limit) {
        return get(cfg.orderUrl(), "/api/v1/orders/" + orderId + "/timeline?limit=" + limit);
    }

    @Override
    public Fetch fulfillment(String fulfillmentRequestId) {
        return get(cfg.fulfillmentUrl(), "/api/fulfillments/" + fulfillmentRequestId);
    }

    @Override
    public Fetch reservation(String reservationId) {
        return get(cfg.inventoryUrl(), "/api/v1/reservations/" + reservationId);
    }

    @Override
    public Fetch info(String service) {
        String base = infoBases.get(service);
        return base == null ? Fetch.of(Fetch.Status.UNREACHABLE, "service not allowlisted") : get(base, "/api/info");
    }

    private Fetch get(String base, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(cfg.readTimeout())
            .header("Accept", "application/json").GET();
        otel.getPropagators().getTextMapPropagator().inject(Context.current(), builder, HttpRequest.Builder::header);
        HttpRequest request = builder.build();
        BoundedHttp.Result result;
        try {
            result = BoundedHttp.send(http, request, maxBytes, cfg.readTimeout());
        } catch (BoundedHttp.Timeout e) {
            return Fetch.of(Fetch.Status.TIMEOUT, "upstream timed out");
        } catch (BoundedHttp.Unreachable e) {
            Log.debugf("upstream %s unreachable", base);
            return Fetch.of(Fetch.Status.UNREACHABLE, "upstream unreachable");
        }
        int status = result.status();
        if (status == 404) {
            return Fetch.of(Fetch.Status.NOT_FOUND, "not found");
        }
        if (status == 401 || status == 403) {
            return Fetch.of(Fetch.Status.UNAUTHORIZED, "upstream refused the read (HTTP " + status + ")");
        }
        if (status != 200) {
            return Fetch.of(Fetch.Status.ERROR, "upstream HTTP " + status);
        }
        if (result.tooLarge()) {
            return Fetch.of(Fetch.Status.ERROR, "upstream response larger than " + maxBytes + " bytes");
        }
        try {
            return Fetch.ok(json.readTree(result.body()));
        } catch (IOException e) {
            return Fetch.of(Fetch.Status.ERROR, "upstream response is not JSON");
        }
    }
}
