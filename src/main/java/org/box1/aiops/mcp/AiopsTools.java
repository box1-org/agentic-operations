package org.box1.aiops.mcp;

import java.util.Map;

import jakarta.inject.Inject;

import org.box1.aiops.analysis.Analysis;
import org.box1.aiops.analysis.AnalysisService;
import org.box1.aiops.evidence.EvidenceService;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * The MCP tool catalog (D-004): read-only evidence tools and the two analysis tools. Each calls the same service
 * method as REST, so authorization, validation and audit are identical. No tool can write, execute an action, run a
 * shell, call the Kubernetes API or fetch an arbitrary URL.
 */
public class AiopsTools {
    private final EvidenceService evidence;
    private final AnalysisService analyses;
    private final ToolAudit audit;

    @Inject
    public AiopsTools(EvidenceService evidence, AnalysisService analyses, ToolAudit audit) {
        this.evidence = evidence;
        this.analyses = analyses;
        this.audit = audit;
    }

    @Tool(name = "get_order_timeline", description = "Read-only. Immutable state timeline of one order from "
        + "order-service, oldest first. Requires aiops-viewer.")
    String getOrderTimeline(@ToolArg(description = "Order ID, ord_...") String orderId) {
        return audit.call("get_order_timeline", orderId, () -> evidence.orderTimeline(orderId));
    }

    @Tool(name = "get_fulfillment_status", description = "Read-only. Fulfillment and ERP acknowledgement status "
        + "for one order, located through the recent event window. Requires aiops-viewer.")
    String getFulfillmentStatus(@ToolArg(description = "Order ID, ord_...") String orderId) {
        return audit.call("get_fulfillment_status", orderId, () -> evidence.fulfillmentStatus(orderId));
    }

    @Tool(name = "get_inventory_reservation", description = "Read-only. The inventory reservation held for one "
        + "order. Requires aiops-viewer.")
    String getInventoryReservation(@ToolArg(description = "Order ID, ord_...") String orderId) {
        return audit.call("get_inventory_reservation", orderId, () -> evidence.inventoryReservation(orderId));
    }

    @Tool(name = "list_recent_events", description = "Read-only. Recent domain events for one order or asset from "
        + "the in-memory Kafka window (hours, not history), by key or by correlationId. Requires aiops-viewer.")
    String listRecentEvents(
            @ToolArg(description = "Order ID (ord_...) or asset ID (asset_...)", required = false) String key,
            @ToolArg(description = "Correlation ID, instead of key", required = false) String correlationId,
            @ToolArg(description = "Maximum events, 1-50, default 20", required = false) Integer limit) {
        if ((key == null || key.isBlank()) && correlationId != null && !correlationId.isBlank()) {
            return audit.call("list_recent_events", null,
                () -> evidence.recentEventsByCorrelation(correlationId, limit));
        }
        return audit.call("list_recent_events", key, () -> evidence.recentEvents(key, limit));
    }

    @Tool(name = "list_telemetry_alerts", description = "Read-only. Telemetry alerts from asset-service. Not "
        + "available in v1; threshold alerts may appear in list_recent_events. Requires aiops-viewer.")
    String listTelemetryAlerts(@ToolArg(description = "Asset ID, asset_...", required = false) String assetId) {
        return audit.call("list_telemetry_alerts", assetId, () -> evidence.telemetryAlerts(assetId));
    }

    @Tool(name = "get_deployed_revision", description = "Read-only. Deployed source revision (gitRef) of one "
        + "service: order-service, inventory-service or fulfillment-adapter. Requires aiops-viewer.")
    String getDeployedRevision(@ToolArg(description = "Service name") String service) {
        return audit.call("get_deployed_revision", service, () -> evidence.deployedRevision(service));
    }

    @Tool(name = "get_trace", description = "Read-only. Summary of one distributed trace from Tempo (tenant dev): "
        + "services, root span, duration, error and slowest spans. Requires aiops-viewer.")
    String getTrace(@ToolArg(description = "Trace ID, 32 lower-case hex characters") String traceId) {
        return audit.call("get_trace", traceId, () -> evidence.traceContent(traceId));
    }

    @Tool(name = "search_logs", description = "Read-only. Log lines from the box1 order-path services in LokiStack "
        + "for one order ID or one trace ID (exactly one), last 72 hours, at most 50. Requires aiops-viewer.")
    String searchLogs(@ToolArg(description = "Order ID, ord_...", required = false) String orderId,
            @ToolArg(description = "Trace ID, 32 hex", required = false) String traceId) {
        return audit.call("search_logs", orderId != null ? orderId : traceId,
            () -> evidence.searchLogs(orderId, traceId));
    }

    @Tool(name = "list_service_alerts", description = "Read-only. Active alerts and down scrape targets for one "
        + "service's namespace: order-service, inventory-service or fulfillment-adapter. Requires aiops-viewer.")
    String listServiceAlerts(@ToolArg(description = "Service name") String service) {
        return audit.call("list_service_alerts", service, () -> evidence.serviceAlerts(service));
    }

    @Tool(name = "analyze_incident", description = "Queue this service's evidence-grounded analysis of an "
        + "incident (inc_<orderId>). Returns an analysisId to read with get_analysis. Proposes actions only; never "
        + "executes anything. Requires aiops-analyst.")
    String analyzeIncident(@ToolArg(description = "Incident ID, inc_ord_...") String incidentId) {
        return audit.call("analyze_incident", incidentId, () -> {
            Analysis queued = analyses.submit(incidentId);
            return Map.of("analysisId", queued.analysisId(), "status", queued.status().name());
        });
    }

    @Tool(name = "get_analysis", description = "Read an analysis: status, summary, cited findings, unknowns, "
        + "evidence and proposed (never executed) actions. Requires aiops-viewer.")
    String getAnalysis(@ToolArg(description = "Analysis ID, ana_...") String analysisId) {
        return audit.call("get_analysis", analysisId, () -> analyses.get(analysisId));
    }
}
