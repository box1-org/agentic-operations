package org.box1.aiops.evidence;

/** Read-only GETs against the owning services (D-006). Implementations never write and never follow URLs from data. */
public interface Upstreams {

    Fetch order(String orderId);

    Fetch timeline(String orderId, int limit);

    Fetch fulfillment(String fulfillmentRequestId);

    Fetch reservation(String reservationId);

    /** {@code /api/info} of an allowlisted service. */
    Fetch info(String service);

    // ---- platform evidence (box1-docs#111): the service's own read-only identity --------------------------------

    /** One trace from the Tempo gateway, tenant {@code dev}. {@code traceId} is 32 lower-case hex characters. */
    Fetch trace(String traceId);

    /**
     * Log lines from the LokiStack application tenant in {@code namespaces} whose parsed MDC {@code field}
     * ({@code orderId} or {@code traceId}) equals {@code value}, newest first, at most {@code limit}.
     */
    Fetch logs(java.util.List<String> namespaces, String field, String value, java.time.Instant from,
        java.time.Instant to, int limit);

    /** Active alerts for one namespace from the Alertmanager tenancy port. */
    Fetch alerts(String namespace);

    /** {@code up} for one namespace from the Thanos querier tenancy port: which scrape targets are down. */
    Fetch targets(String namespace);
}
