package org.box1.aiops.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Flattens raw platform responses into small, bounded evidence (box1-docs#111). Every string is cut to
 * {@link Sanitizer#MAX_STRING}; only named fields survive; nothing nested from the upstream is passed on. All text is
 * still untrusted data: log lines and span names are written by services and may carry injected instructions.
 */
final class PlatformShapes {
    static final int MAX_SPANS_LISTED = 20;
    static final int MAX_ALERTS = 30;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private PlatformShapes() {
    }

    // ---- Tempo -----------------------------------------------------------------------------------------------------

    private record Span(String service, String name, String spanId, String parentId, long startNanos, long endNanos,
            int statusCode, String statusMessage) {
        long durationMs() {
            return Math.max(0, (endNanos - startNanos) / 1_000_000);
        }
    }

    /** A trace summary: span count, services, root span, duration, and the error and slowest spans. */
    static ObjectNode trace(String traceId, JsonNode body) {
        List<Span> spans = new ArrayList<>();
        JsonNode batches = body.has("batches") ? body.get("batches") : body.path("resourceSpans");
        for (JsonNode batch : iterable(batches)) {
            String service = attribute(batch.path("resource").path("attributes"), "service.name");
            JsonNode scopes = batch.has("scopeSpans") ? batch.get("scopeSpans") : batch.path("instrumentationLibrarySpans");
            for (JsonNode scope : iterable(scopes)) {
                for (JsonNode s : iterable(scope.path("spans"))) {
                    spans.add(new Span(service, s.path("name").asText(""), s.path("spanId").asText(""),
                        s.path("parentSpanId").asText(""), longOf(s.path("startTimeUnixNano")),
                        longOf(s.path("endTimeUnixNano")), statusCode(s.path("status").path("code")),
                        s.path("status").path("message").asText("")));
                }
            }
        }
        ObjectNode out = F.objectNode();
        out.put("traceId", traceId);
        out.put("spanCount", spans.size());
        if (spans.isEmpty()) {
            return out;
        }
        Set<String> services = new LinkedHashSet<>();
        spans.forEach(s -> services.add(s.service()));
        ArrayNode svc = out.putArray("services");
        services.stream().limit(20).forEach(s -> svc.add(cut(s)));
        long start = spans.stream().mapToLong(Span::startNanos).min().orElse(0);
        long end = spans.stream().mapToLong(Span::endNanos).max().orElse(0);
        out.put("start", Instant.ofEpochSecond(0, start).toString());
        out.put("durationMs", Math.max(0, (end - start) / 1_000_000));
        spans.stream().filter(s -> s.parentId().isEmpty()).min(Comparator.comparingLong(Span::startNanos))
            .ifPresent(root -> out.put("rootSpan", cut(root.service() + " " + root.name())));
        List<Span> errors = spans.stream().filter(s -> s.statusCode() == 2).limit(MAX_SPANS_LISTED).toList();
        out.put("errorSpanCount", spans.stream().filter(s -> s.statusCode() == 2).count());
        ArrayNode err = out.putArray("errorSpans");
        errors.forEach(s -> err.add(span(s, true)));
        ArrayNode slow = out.putArray("slowestSpans");
        spans.stream().sorted(Comparator.comparingLong(Span::durationMs).reversed()).limit(5)
            .forEach(s -> slow.add(span(s, false)));
        return out;
    }

    private static ObjectNode span(Span s, boolean withStatus) {
        ObjectNode o = F.objectNode().put("service", cut(s.service())).put("name", cut(s.name()))
            .put("durationMs", s.durationMs());
        if (withStatus && !s.statusMessage().isEmpty()) {
            o.put("statusMessage", cut(s.statusMessage()));
        }
        return o;
    }

    /** OTLP status code as a number (0 unset, 1 ok, 2 error), from either the enum name or the number. */
    private static int statusCode(JsonNode code) {
        if (code.isInt()) {
            return code.intValue();
        }
        String t = code.asText("");
        return t.endsWith("ERROR") ? 2 : t.endsWith("OK") ? 1 : 0;
    }

    private static String attribute(JsonNode attributes, String key) {
        for (JsonNode a : iterable(attributes)) {
            if (key.equals(a.path("key").asText())) {
                return a.path("value").path("stringValue").asText("unknown");
            }
        }
        return "unknown";
    }

    // ---- LokiStack ---------------------------------------------------------------------------------------------

    /** Log lines, oldest first (so the evidence budget drops the oldest), with level, logger and message only. */
    static ArrayNode logs(JsonNode body, ObjectMapper json, int limit) {
        record Line(long nanos, String namespace, String text) {
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode stream : iterable(body.path("data").path("result"))) {
            String ns = stream.path("stream").path("kubernetes_namespace_name").asText("");
            for (JsonNode v : iterable(stream.path("values"))) {
                lines.add(new Line(longOf(v.path(0)), ns, v.path(1).asText("")));
            }
        }
        lines.sort(Comparator.comparingLong(Line::nanos).reversed());
        List<Line> kept = new ArrayList<>(lines.subList(0, Math.min(limit, lines.size())));
        kept.sort(Comparator.comparingLong(Line::nanos));
        ArrayNode out = F.arrayNode();
        for (Line l : kept) {
            ObjectNode o = out.addObject();
            o.put("timestamp", Instant.ofEpochSecond(0, l.nanos()).toString());
            o.put("namespace", cut(l.namespace()));
            JsonNode parsed = parse(json, l.text());
            if (parsed != null && parsed.isObject()) {
                putText(o, "level", parsed.path("level"));
                putText(o, "logger", parsed.path("loggerName"));
                putText(o, "message", parsed.has("message") ? parsed.get("message") : parsed.path("msg"));
                putText(o, "traceId", parsed.path("mdc").path("traceId"));
                putText(o, "exception", parsed.path("exception").path("exceptionType"));
            } else {
                o.put("message", cut(l.text()));
            }
        }
        return out;
    }

    // ---- Alertmanager and Thanos -------------------------------------------------------------------------------

    /** Active alerts with name, severity, state, start, namespace and summary. */
    static ArrayNode alerts(JsonNode body) {
        ArrayNode out = F.arrayNode();
        for (JsonNode a : iterable(body)) {
            if (out.size() >= MAX_ALERTS) {
                break;
            }
            ObjectNode o = out.addObject();
            putText(o, "alertname", a.path("labels").path("alertname"));
            putText(o, "severity", a.path("labels").path("severity"));
            putText(o, "namespace", a.path("labels").path("namespace"));
            putText(o, "state", a.path("status").path("state"));
            putText(o, "startsAt", a.path("startsAt"));
            putText(o, "summary", a.path("annotations").has("summary") ? a.path("annotations").get("summary")
                : a.path("annotations").path("description"));
        }
        return out;
    }

    /** Scrape targets that are down ({@code up == 0}), and how many targets were seen. */
    static ObjectNode targets(String namespace, JsonNode body) {
        ObjectNode out = F.objectNode().put("namespace", namespace);
        int seen = 0;
        ArrayNode down = out.putArray("down");
        for (JsonNode r : iterable(body.path("data").path("result"))) {
            seen++;
            if ("0".equals(r.path("value").path(1).asText()) && down.size() < 20) {
                ObjectNode d = down.addObject();
                putText(d, "job", r.path("metric").path("job"));
                putText(d, "pod", r.path("metric").path("pod"));
            }
        }
        out.put("targets", seen);
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private static long longOf(JsonNode n) {
        if (n.isNumber()) {
            return n.longValue();
        }
        try {
            return Long.parseLong(n.asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonNode parse(ObjectMapper json, String text) {
        if (text == null || !text.startsWith("{")) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putText(ObjectNode o, String field, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull() && !value.isContainerNode()) {
            o.put(field, cut(value.asText()));
        }
    }

    static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > Sanitizer.MAX_STRING ? s.substring(0, Sanitizer.MAX_STRING) + "..." : s;
    }
}
