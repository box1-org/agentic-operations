package org.box1.aiops.security;

/** The caller is anonymous (401) or lacks the role or an accepted client (403). */
public class AccessDenied extends RuntimeException {
    private final boolean unauthenticated;

    public AccessDenied(boolean unauthenticated, String message) {
        super(message, null, false, false);
        this.unauthenticated = unauthenticated;
    }

    public boolean unauthenticated() {
        return unauthenticated;
    }
}
