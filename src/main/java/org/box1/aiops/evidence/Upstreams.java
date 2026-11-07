package org.box1.aiops.evidence;

/** Read-only GETs against the owning services (D-006). Implementations never write and never follow URLs from data. */
public interface Upstreams {

    Fetch order(String orderId);

    Fetch timeline(String orderId, int limit);

    Fetch fulfillment(String fulfillmentRequestId);

    Fetch reservation(String reservationId);

    /** {@code /api/info} of an allowlisted service. */
    Fetch info(String service);
}
