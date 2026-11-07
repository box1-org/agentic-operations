package org.box1.aiops.analysis;

/** The model queue is full (D-008). REST answers 429 with Retry-After; MCP returns a tool error. */
public class Busy extends RuntimeException {
    public Busy(String message) {
        super(message, null, false, false);
    }
}
