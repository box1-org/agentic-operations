package org.box1.aiops.api;

/** RFC 9457 problem body with a stable machine-readable code. */
public record Problem(String type, String title, int status, String code, String detail) {
    public static final String MEDIA_TYPE = "application/problem+json";
}
