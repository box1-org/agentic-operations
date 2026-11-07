package org.box1.aiops;

/** A caller-supplied value failed validation. Maps to 400 (REST) or a tool error (MCP). */
public class InvalidArgument extends RuntimeException {
    private final String code;

    public InvalidArgument(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
