package org.box1.aiops.analysis;

import java.time.Instant;
import java.util.List;

import org.box1.aiops.evidence.EvidenceItem;

/** An analysis as returned by REST and MCP. Suggested actions are proposals only; nothing here is ever executed. */
public record Analysis(String analysisId, String incidentId, String orderId, String incidentType, Status status,
        String statusDetail, Instant createdAt, Instant completedAt, String summary, List<Finding> findings,
        List<String> unknowns, List<SuggestedAction> suggestedActions, List<EvidenceItem> evidence, ModelInfo model,
        Quality quality) {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, DEGRADED, MODEL_UNAVAILABLE, MODEL_INVALID_OUTPUT, FAILED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING;
        }
    }

    public record Finding(String statement, List<String> evidenceRefs, String confidence) {
    }

    /** {@code executable} is always false in v1 (D-011). */
    public record SuggestedAction(String type, String description, String expectedEffect, String rollback,
            boolean executable) {
    }

    public record ModelInfo(String modelId, String contextProfile, Integer promptTokens, Integer completionTokens) {
    }

    /** Findings kept, findings dropped for missing or unknown citations, actions dropped by the allowlist. */
    public record Quality(int citedFindings, int unsupportedAssertions, int droppedActions) {
    }

    static Analysis queued(String id, String incidentId, String orderId, Instant now) {
        return new Analysis(id, incidentId, orderId, null, Status.QUEUED, null, now, null, null, List.of(), List.of(),
            List.of(), List.of(), null, null);
    }

    Analysis running() {
        return new Analysis(analysisId, incidentId, orderId, incidentType, Status.RUNNING, null, createdAt, null, null,
            List.of(), List.of(), List.of(), List.of(), null, null);
    }
}
