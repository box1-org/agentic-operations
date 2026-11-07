package org.box1.aiops.security;

/** Client roles of the Keycloak client {@code agentic-operations} (D-005). Operator-only in v1. */
public final class Roles {
    /** Read evidence and analyses. */
    public static final String VIEWER = "aiops-viewer";
    /** Viewer, plus start analyses. */
    public static final String ANALYST = "aiops-analyst";

    private Roles() {
    }
}
