package org.box1.aiops;

/** An incident or analysis that does not exist (or no longer exists in memory). */
public class NotFound extends RuntimeException {
    private final String code;

    public NotFound(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
