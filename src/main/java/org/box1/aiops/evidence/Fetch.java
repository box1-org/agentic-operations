package org.box1.aiops.evidence;

import com.fasterxml.jackson.databind.JsonNode;

/** One upstream read. {@code detail} is a fixed, service-written string, never upstream text. */
public record Fetch(Status status, JsonNode body, String detail) {

    public enum Status {
        OK, NOT_FOUND, UNAUTHORIZED, ERROR, TIMEOUT, UNREACHABLE
    }

    public static Fetch ok(JsonNode body) {
        return new Fetch(Status.OK, body, null);
    }

    public static Fetch of(Status status, String detail) {
        return new Fetch(status, null, detail);
    }

    public boolean isOk() {
        return status == Status.OK && body != null;
    }
}
