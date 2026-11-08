package org.box1.aiops;

/** A request that is valid but not possible now, for example a second message while a chat turn is running. */
public class Conflict extends RuntimeException {
    private final String code;

    public Conflict(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
