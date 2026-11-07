package org.box1.aiops.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;

/** Plain GETs with bounded time and size. Paths are built only from validated IDs (see {@link org.box1.aiops.Ids}). */
@ApplicationScoped
public class HttpUpstreams implements Upstreams {
    private final HttpClient http;
    private final ObjectMapper json;
    private final AiopsConfig.Upstream cfg;
    private final int maxBytes;
    private final Map<String, String> infoBases;

    @Inject
    public HttpUpstreams(AiopsConfig config, ObjectMapper json) {
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(cfg.readTimeout())
            .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 404) {
                    return Fetch.of(Fetch.Status.NOT_FOUND, "not found");
                }
                if (status == 401 || status == 403) {
                    return Fetch.of(Fetch.Status.UNAUTHORIZED, "upstream refused the read (HTTP " + status + ")");
                }
                if (status != 200) {
                    return Fetch.of(Fetch.Status.ERROR, "upstream HTTP " + status);
                }
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    return Fetch.of(Fetch.Status.ERROR, "upstream response larger than " + maxBytes + " bytes");
                }
                return Fetch.ok(json.readTree(bytes));
            }
        } catch (HttpTimeoutException e) {
            return Fetch.of(Fetch.Status.TIMEOUT, "upstream timed out");
        } catch (IOException e) {
            Log.debugf("upstream %s unavailable: %s", base, e.getClass().getSimpleName());
            return Fetch.of(Fetch.Status.UNREACHABLE, "upstream unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fetch.of(Fetch.Status.UNREACHABLE, "interrupted");
        }
    }
}
