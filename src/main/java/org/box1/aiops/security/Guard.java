package org.box1.aiops.security;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.eclipse.microprofile.jwt.JsonWebToken;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * The one authorization point for REST and MCP (D-004, D-005). Every service method that reads evidence or analyses
 * calls it before any upstream call. MCP tool filters are never relied on: they fail open when they throw.
 */
@ApplicationScoped
public class Guard {
    private final SecurityIdentity identity;
    private final Set<String> allowedAzp;
    private final boolean requireJwt;

    @Inject
    public Guard(SecurityIdentity identity, AiopsConfig config) {
        this.identity = identity;
        this.allowedAzp = Set.copyOf(config.auth().allowedAzp());
        this.requireJwt = config.auth().requireJwt();
    }

    public void requireViewer() {
        require(Roles.VIEWER, Roles.ANALYST);
    }

    public void requireAnalyst() {
        require(Roles.ANALYST);
    }

    /** Subject for audit records; never returned to callers. */
    public String subject() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName();
    }

    private void require(String... anyOf) {
        if (identity.isAnonymous()) {
            throw new AccessDenied(true, "authentication required");
        }
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            Object azp = jwt.getClaim("azp");
            if (azp == null || !allowedAzp.contains(azp.toString())) {
                throw new AccessDenied(false, "client not accepted");
            }
        } else if (requireJwt) {
            throw new AccessDenied(false, "a bearer token is required");
        }
        for (String role : anyOf) {
            if (identity.hasRole(role)) {
                return;
            }
        }
        throw new AccessDenied(false, "role " + String.join(" or ", anyOf) + " required");
    }
}
