package org.box1.aiops.analysis;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.Ids;
import org.box1.aiops.NotFound;
import org.box1.aiops.evidence.EvidenceItem;
import org.box1.aiops.evidence.EvidenceService;
import org.box1.aiops.evidence.Fetch;
import org.box1.aiops.evidence.Upstreams;
import org.box1.aiops.model.ModelClient;
import org.box1.aiops.model.ModelRequest;
import org.box1.aiops.model.ModelResult;
import org.box1.aiops.security.Guard;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.MDC;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.quarkus.logging.Log;

/**
 * Bounded, evidence-grounded analysis (D-002 to D-011). Submission authorizes the caller, resolves the incident and
 * queues the work; a fixed-size worker pool then gathers evidence, calls the model once and validates the answer.
 * Nothing here can execute an action.
 */
@io.quarkus.runtime.Startup
@ApplicationScoped
public class AnalysisService {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Read failures of an expected source. Service-declared gaps (UNAVAILABLE) do not degrade an analysis. */
    static final String UNSUPPORTED_SUMMARY = "No supported diagnosis: none of the model's findings cited "
        + "evidence that exists. Review the evidence directly.";
    static final Set<String> DEGRADING = Set.of("NOT_FOUND", "UNAUTHORIZED", "ERROR", "TIMEOUT", "UNREACHABLE", "CONFLICTING");

    private final Guard guard;
    private final Upstreams upstreams;
    private final EvidenceService evidence;
    private final ModelClient model;
    private final ObjectMapper json;
    private final AiopsConfig config;
    private final MeterRegistry registry;
    private final AnalysisStore store;
    private final ModelGate gate;
    private final Clock clock = Clock.systemUTC();
    private final Tracer tracer;

    @Inject
    public AnalysisService(Guard guard, Upstreams upstreams, EvidenceService evidence, ModelClient model,
            ObjectMapper json, AiopsConfig config, MeterRegistry registry, Tracer tracer, ModelGate gate) {
        this.tracer = tracer;
        this.guard = guard;
        this.upstreams = upstreams;
        this.evidence = evidence;
        this.model = model;
        this.json = json;
        this.config = config;
        this.registry = registry;
        AiopsConfig.Analysis a = config.analysis();
        this.store = new AnalysisStore(clock, a.ttl(), a.maxEntries());
        this.gate = gate;
    }

    /** Authorizes, resolves the incident and queues the analysis on the shared model queue. Unknown orders never reach the model. */
    public Analysis submit(String incidentId) {
        guard.requireAnalyst();
        String orderId = Ids.orderOfIncident(incidentId);
        Fetch order = upstreams.order(orderId);
        if (order.status() == Fetch.Status.NOT_FOUND) {
            throw new NotFound("INCIDENT_NOT_FOUND", "no order " + orderId + " for incident " + incidentId);
        }
        String id = "ana_" + HexFormat.of().formatHex(bytes());
        Analysis queued = Analysis.queued(id, incidentId, orderId, clock.instant());
        String subject = guard.subject();
        // Reserve the queue slot first: a BUSY rejection must never touch the store (review A-2).
        Runnable task = Context.current().wrap(() -> run(queued, subject));
        if (!gate.tryExecute(task)) {
            registry.counter("aiops_analyses_total", "status", "BUSY").increment();
            throw new Busy("analysis queue is full; retry later");
        }
        store.putIfAbsent(queued, subject);
        Log.infof("analysis queued analysisId=%s incidentId=%s subject=%s", id, incidentId, subject);
        return queued;
    }

    public Analysis get(String analysisId) {
        guard.requireViewer();
        return getAuthorized(analysisId);
    }

    /**
     * For a caller that already checked {@code requireViewer()} on its own request thread (the Assistant's tool loop
     * runs on a model worker, which carries no request identity; D-016).
     */
    public Analysis getAuthorized(String analysisId) {
        Ids.analysis(analysisId);
        return store.get(analysisId).orElseThrow(() -> new NotFound("ANALYSIS_NOT_FOUND",
            "no analysis " + analysisId + "; analyses are kept in memory for " + config.analysis().ttl()
                + " and do not survive a restart"));
    }

    /** Runs on a worker with the submitting request's trace context (review A-6, O-1). */
    private void run(Analysis queued, String subject) {
        Timer.Sample sample = Timer.start(registry);
        Span span = tracer.spanBuilder("aiops.analysis").setAttribute("aiops.analysis_id", queued.analysisId())
            .setAttribute("aiops.incident_id", queued.incidentId()).startSpan();
        MDC.put("analysisId", queued.analysisId());
        Analysis result;
        try (Scope ignored = span.makeCurrent()) {
            store.put(queued.running(), subject);
            result = analyze(queued);
        } catch (RuntimeException e) {
            Log.errorf("analysis %s failed: %s at %s", queued.analysisId(), e.getClass().getSimpleName(), where(e));
            span.setStatus(StatusCode.ERROR);
            result = finish(queued, null, Analysis.Status.FAILED, "internal error", List.of(), null, null, null);
        }
        store.put(result, subject);
        span.setAttribute("aiops.status", result.status().name());
        span.end();
        sample.stop(registry.timer("aiops_analysis_duration_seconds", "status", result.status().name()));
        registry.counter("aiops_analyses_total", "status", result.status().name()).increment();
        Log.infof("analysis finished analysisId=%s status=%s", result.analysisId(), result.status());
        MDC.remove("analysisId");
    }

    /** First org.box1 stack frame, for diagnosis without the message (which may contain evidence) (review O-4). */
    static String where(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("org.box1.")) {
                return f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        return "unknown";
    }

    private Analysis analyze(Analysis queued) {
        Profile profile = Profile.of(config.model().contextProfile());
        EvidenceService.Bundle bundle = evidence.gather(queued.orderId(), profile.evidenceBytes());
        List<EvidenceItem> items = bundle.items();
        String type = IncidentRules.derive(bundle.order(), config.analysis().stuckThreshold(), clock.instant());
        Long awaiting = IncidentRules.minutesAwaitingErp(bundle.order(), clock.instant());
        Analysis typed = new Analysis(queued.analysisId(), queued.incidentId(), queued.orderId(), type,
            Analysis.Status.RUNNING, null, queued.createdAt(), null, null, List.of(), List.of(), List.of(), items,
            null, null);
        if (bundle.order() == null) {
            return finish(typed, items, Analysis.Status.DEGRADED,
                "order evidence unavailable (" + bundle.orderStatus() + "); no analysis was attempted",
                serviceUnknowns(items), null, null, null);
        }
        ModelRequest request = new ModelRequest(Prompt.SYSTEM,
            Prompt.user(json, queued.incidentId(), type, queued.orderId(), awaiting, items), Prompt.schema(json),
            profile.maxOutputTokens());
        ModelResult answer = model.complete(request);
        Analysis.ModelInfo info = new Analysis.ModelInfo(model.modelId(), profile.name(), answer.promptTokens(),
            answer.completionTokens());
        if (answer.promptTokens() != null) {
            registry.counter("aiops_model_tokens_total", "kind", "prompt").increment(answer.promptTokens());
        }
        if (answer.completionTokens() != null) {
            registry.counter("aiops_model_tokens_total", "kind", "completion").increment(answer.completionTokens());
        }
        Log.infof("model call analysisId=%s outcome=%s promptTokens=%s completionTokens=%s profile=%s",
            queued.analysisId(), answer.outcome(), answer.promptTokens(), answer.completionTokens(), profile.name());
        switch (answer.outcome()) {
            case UNAVAILABLE, TIMEOUT -> {
                return finish(typed, items, Analysis.Status.MODEL_UNAVAILABLE, answer.detail(), serviceUnknowns(items),
                    info, null, null);
            }
            case INVALID -> {
                return finish(typed, items, Analysis.Status.MODEL_INVALID_OUTPUT, answer.detail(),
                    serviceUnknowns(items), info, null, null);
            }
            default -> {
            }
        }
        Set<String> refs = new HashSet<>();
        items.forEach(i -> refs.add(i.ref()));
        OutputValidator.Validated v;
        try {
            v = OutputValidator.validate(json, answer.content(), refs);
        } catch (OutputValidator.Invalid e) {
            return finish(typed, items, Analysis.Status.MODEL_INVALID_OUTPUT, e.getMessage(), serviceUnknowns(items),
                info, null, null);
        }
        boolean degraded = items.stream().anyMatch(i -> EvidenceService.CORE.contains(i.type())
            && DEGRADING.contains(i.retrievalStatus()));
        List<String> unknowns = new ArrayList<>(serviceUnknowns(items));
        unknowns.addAll(v.unknowns());
        String summary = v.summary();
        String detail = degraded ? "some expected evidence could not be read" : null;
        if (v.findings().isEmpty() && v.quality().unsupportedAssertions() > 0) {
            // Every finding the model made was uncited, so its prose is not shown as the answer (review A-1).
            summary = UNSUPPORTED_SUMMARY;
            detail = (detail == null ? "" : detail + "; ") + "no finding was supported by the evidence";
        }
        return new Analysis(typed.analysisId(), typed.incidentId(), typed.orderId(), type,
            degraded ? Analysis.Status.DEGRADED : Analysis.Status.COMPLETED, detail, typed.createdAt(),
            clock.instant(), summary, v.findings(), List.copyOf(unknowns), v.actions(), items, info, v.quality());
    }

    private Analysis finish(Analysis base, List<EvidenceItem> items, Analysis.Status status, String detail,
            List<String> unknowns, Analysis.ModelInfo info, String summary, Analysis.Quality quality) {
        return new Analysis(base.analysisId(), base.incidentId(), base.orderId(), base.incidentType(), status, detail,
            base.createdAt(), clock.instant(), summary, List.of(), unknowns == null ? List.of() : unknowns, List.of(),
            items == null ? List.of() : items, info, quality);
    }

    /** Written by the service, not the model: every source that could not be read. */
    static List<String> serviceUnknowns(List<EvidenceItem> items) {
        List<String> out = new ArrayList<>();
        for (EvidenceItem i : items) {
            if (!i.ok()) {
                out.add(i.ref() + " " + i.type() + ": " + i.retrievalStatus()
                    + (i.detail() == null ? "" : " - " + i.detail()));
            }
        }
        return out;
    }

    private static byte[] bytes() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return b;
    }

    /** D-008 budget profiles. Evidence bytes approximate tokens at about three bytes per token. */
    public record Profile(String name, int evidenceBytes, int maxOutputTokens) {
        public static Profile of(String name) {
            return "16k".equalsIgnoreCase(name) ? new Profile("16k", 24_576, 2048)
                : new Profile("32k", 49_152, 4096);
        }
    }
}
