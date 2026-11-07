package org.box1.aiops.api;

import java.net.URI;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.box1.aiops.InvalidArgument;
import org.box1.aiops.NotFound;
import org.box1.aiops.analysis.Analysis;
import org.box1.aiops.analysis.AnalysisService;
import org.box1.aiops.analysis.Busy;
import org.box1.aiops.security.AccessDenied;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.smallrye.common.annotation.Blocking;

/** REST adapter over {@link AnalysisService}. Read and propose only: there is no route that changes any system. */
@Path("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
@SecurityRequirement(name = "bearer")
public class AnalysisResource {
    private final AnalysisService analyses;

    @Inject
    public AnalysisResource(AnalysisService analyses) {
        this.analyses = analyses;
    }

    /** Accepted: the analysis is queued. */
    public record Submitted(String analysisId, String status, String location) {
    }

    @POST
    @Path("/incidents/{incidentId}/analyze")
    @Operation(operationId = "analyzeIncident",
        summary = "Queue an evidence-grounded analysis of an incident (inc_<orderId>). Never executes an action")
    @APIResponse(responseCode = "202", description = "Queued", content = @Content(mediaType = MediaType.APPLICATION_JSON,
        schema = @Schema(implementation = Submitted.class)))
    @APIResponse(responseCode = "400", description = "Invalid incident ID")
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks aiops-analyst or comes from an unaccepted client")
    @APIResponse(responseCode = "404", description = "No order for this incident")
    @APIResponse(responseCode = "429", description = "Model queue full; see Retry-After")
    public Response analyze(@PathParam("incidentId") String incidentId) {
        Analysis queued = analyses.submit(incidentId);
        String location = "/api/v1/ai/analyses/" + queued.analysisId();
        return Response.accepted(new Submitted(queued.analysisId(), queued.status().name(), location))
            .location(URI.create(location)).build();
    }

    @GET
    @Path("/analyses/{analysisId}")
    @Operation(operationId = "getAnalysis",
        summary = "Read an analysis: status, summary, cited findings, unknowns, evidence and proposed actions")
    @APIResponse(responseCode = "200", description = "Analysis", content = @Content(
        mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Analysis.class)))
    @APIResponse(responseCode = "400", description = "Invalid analysis ID")
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks aiops-viewer")
    @APIResponse(responseCode = "404", description = "Unknown, expired, or lost on restart")
    public Analysis get(@PathParam("analysisId") String analysisId) {
        return analyses.get(analysisId);
    }

    @ServerExceptionMapper
    Response denied(AccessDenied e) {
        int status = e.unauthenticated() ? 401 : 403;
        return problem(status, e.unauthenticated() ? "UNAUTHENTICATED" : "FORBIDDEN", e.getMessage());
    }

    @ServerExceptionMapper
    Response invalid(InvalidArgument e) {
        return problem(400, e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    Response notFound(NotFound e) {
        return problem(404, e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    Response busy(Busy e) {
        Response r = problem(429, "BUSY", e.getMessage());
        return Response.fromResponse(r).header("Retry-After", "30").build();
    }

    private static Response problem(int status, String code, String detail) {
        return Response.status(status).type(Problem.MEDIA_TYPE)
            .entity(new Problem("about:blank", Response.Status.fromStatusCode(status) == null ? code
                : Response.Status.fromStatusCode(status).getReasonPhrase(), status, code, detail)).build();
    }
}
