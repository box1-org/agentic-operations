package org.box1.aiops.api;

import jakarta.ws.rs.core.Response;

import org.box1.aiops.Conflict;
import org.box1.aiops.InvalidArgument;
import org.box1.aiops.NotFound;
import org.box1.aiops.analysis.Busy;
import org.box1.aiops.security.AccessDenied;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Domain errors as RFC 9457 problems, for every REST resource (analyses and the Assistant's conversations). */
public class ProblemMappers {

    @ServerExceptionMapper
    public Response denied(AccessDenied e) {
        int status = e.unauthenticated() ? 401 : 403;
        return problem(status, e.unauthenticated() ? "UNAUTHENTICATED" : "FORBIDDEN", e.getMessage());
    }

    @ServerExceptionMapper
    public Response invalid(InvalidArgument e) {
        return problem(400, e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response notFound(NotFound e) {
        return problem(404, e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response conflict(Conflict e) {
        return problem(409, e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response busy(Busy e) {
        Response r = problem(429, "BUSY", e.getMessage());
        return Response.fromResponse(r).header("Retry-After", "30").build();
    }

    static Response problem(int status, String code, String detail) {
        return Response.status(status).type(Problem.MEDIA_TYPE)
            .entity(new Problem("about:blank", Response.Status.fromStatusCode(status) == null ? code
                : Response.Status.fromStatusCode(status).getReasonPhrase(), status, code, detail)).build();
    }
}
