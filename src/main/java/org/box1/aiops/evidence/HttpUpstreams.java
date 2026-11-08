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
    private final AiopsConfig.Platform platformCfg;
    private final PlatformClient platform;
    private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final java.util.regex.Pattern HEX32 = java.util.regex.Pattern.compile("^[0-9a-f]{32}$");
    private static final java.util.Set<String> LOG_FIELDS = java.util.Set.of("orderId", "traceId");

    @Inject
    public HttpUpstreams(AiopsConfig config, ObjectMapper json, OpenTelemetry otel) {
        this.otel = otel;
        this.cfg = config.upstream();
        this.json = json;
        this.maxBytes = config.evidence().sourceMaxBytes();
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(cfg.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
        this.platformCfg = config.platform();
        this.platform = new PlatformClient(platformCfg, cfg.connectTimeout(), json, otel);
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

    @Override
    public Fetch trace(String traceId) {
        if (traceId == null || !HEX32.matcher(traceId).matches()) {
            return Fetch.of(Fetch.Status.ERROR, "invalid trace ID");
        }
        return platform.get(platformCfg.tempoUrl(), "Tempo", "/api/traces/" + traceId);
    }

    @Override
    public Fetch logs(java.util.List<String> namespaces, String field, String value, java.time.Instant from,
            java.time.Instant to, int limit) {
        if (!LOG_FIELDS.contains(field) || value == null || !value.matches("^[A-Za-z0-9_-]{1,128}$")
            || namespaces.isEmpty() || !namespaces.stream().allMatch(n -> NAMESPACE.matcher(n).matches())) {
            return Fetch.of(Fetch.Status.ERROR, "invalid log query");
        }
        String query = "{kubernetes_namespace_name=~\"" + String.join("|", namespaces) + "\"} | json | structured_mdc_"
            + field + "=\"" + value + "\"";
        return platform.get(platformCfg.lokiUrl(), "LokiStack", "/loki/api/v1/query_range?query=" + enc(query)
            + "&start=" + nanos(from) + "&end=" + nanos(to) + "&limit=" + limit + "&direction=backward");
    }

    @Override
    public Fetch alerts(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            return Fetch.of(Fetch.Status.ERROR, "invalid namespace");
        }
        return platform.get(platformCfg.alertmanagerTenancyUrl(), "Alertmanager", "/api/v2/alerts?namespace="
            + namespace + "&active=true&silenced=false&inhibited=false");
    }

    @Override
    public Fetch targets(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            return Fetch.of(Fetch.Status.ERROR, "invalid namespace");
        }
        return platform.get(platformCfg.thanosTenancyUrl(), "Thanos querier", "/api/v1/query?namespace=" + namespace
            + "&query=" + enc("up"));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nanos(java.time.Instant t) {
        return java.math.BigInteger.valueOf(t.getEpochSecond()).multiply(java.math.BigInteger.valueOf(1_000_000_000L))
            .add(java.math.BigInteger.valueOf(t.getNano())).toString();
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
