package org.box1.aiops.chat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.Conflict;
import org.box1.aiops.Ids;
import org.box1.aiops.InvalidArgument;
import org.box1.aiops.NotFound;
import org.box1.aiops.analysis.AnalysisService;
import org.box1.aiops.analysis.Busy;
import org.box1.aiops.analysis.ModelGate;
import org.box1.aiops.chat.Conversation.Status;
import org.box1.aiops.chat.Conversation.Step;
import org.box1.aiops.chat.Conversation.Turn;
import org.box1.aiops.model.ChatMessage;
import org.box1.aiops.model.ChatRequest;
import org.box1.aiops.model.ChatResult;
import org.box1.aiops.model.ModelClient;
import org.box1.aiops.model.ToolCall;
import org.box1.aiops.security.Guard;

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
 * The Assistant's conversations (D-016). A message is authorized on the request thread (viewer role), queued on the
 * shared model queue, and answered on a worker by a bounded loop: the model may call the read-only {@link ChatTools},
 * at most {@code max-tool-calls} times over {@code max-model-rounds} calls, within {@code turn-deadline}. Nothing here
 * writes to any system. Logs carry IDs, counts and decisions, never questions, answers or evidence (D-012).
 */
@ApplicationScoped
public class ChatService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Guard guard;
    private final ModelClient model;
    private final ChatTools tools;
    private final ModelGate gate;
    private final AiopsConfig config;
    private final MeterRegistry registry;
    private final Tracer tracer;
    private final Clock clock = Clock.systemUTC();
    private final ConversationStore store;

    @Inject
    public ChatService(Guard guard, ModelClient model, ChatTools tools, ModelGate gate, AiopsConfig config,
            MeterRegistry registry, Tracer tracer) {
        this.guard = guard;
        this.model = model;
        this.tools = tools;
        this.gate = gate;
        this.config = config;
        this.registry = registry;
        this.tracer = tracer;
        this.store = new ConversationStore(clock, config.chat().ttl(), config.chat().maxConversations());
    }

    public Conversation create() {
        guard.requireViewer();
        Instant now = clock.instant();
        Conversation c = new Conversation("conv_" + HexFormat.of().formatHex(bytes()), now, now, List.of());
        store.put(c, guard.subject());
        Log.infof("chat conversation created conversationId=%s subject=%s", c.conversationId(), guard.subject());
        return c;
    }

    public Conversation get(String conversationId) {
        guard.requireViewer();
        return find(conversationId, guard.subject());
    }

    /** Queues one message. A second message while a turn runs is refused; a full model queue is BUSY. */
    public Turn send(String conversationId, String text) {
        guard.requireViewer();
        String subject = guard.subject();
        Conversation c = find(conversationId, subject);
        AiopsConfig.Chat cfg = config.chat();
        if (text == null || text.isBlank()) {
            throw new InvalidArgument("INVALID_MESSAGE", "text must not be empty");
        }
        if (text.length() > cfg.messageMaxChars()) {
            throw new InvalidArgument("INVALID_MESSAGE", "text must be at most " + cfg.messageMaxChars() + " characters");
        }
        Turn last = c.last();
        if (last != null && !last.status().finished()) {
            throw new Conflict("TURN_IN_PROGRESS", "the previous message is still being answered");
        }
        if (c.turns().size() >= cfg.maxTurns()) {
            throw new Conflict("CONVERSATION_FULL", "this conversation has " + cfg.maxTurns()
                + " messages; start a new one");
        }
        Instant now = clock.instant();
        Turn turn = new Turn("turn_" + (c.turns().size() + 1), text.strip(), Status.QUEUED, null, null, List.of(), null,
            now, null);
        // Recorded before queueing so the worker always finds it; removed again if the queue is full (BUSY).
        store.update(conversationId, conv -> conv.append(turn, now));
        Runnable task = Context.current().wrap(() -> run(conversationId, turn.turnId(), subject));
        if (!gate.tryExecute(task)) {
            store.update(conversationId, conv -> conv.without(turn.turnId()));
            registry.counter("aiops_chat_turns_total", "status", "BUSY").increment();
            throw new Busy("the model queue is full; retry later");
        }
        Log.infof("chat turn queued conversationId=%s turnId=%s subject=%s chars=%d", conversationId, turn.turnId(),
            subject, turn.question().length());
        return turn;
    }

    private Conversation find(String conversationId, String subject) {
        Ids.conversation(conversationId);
        return store.get(conversationId, subject).orElseThrow(() -> new NotFound("CONVERSATION_NOT_FOUND",
            "no conversation " + conversationId + "; conversations are kept in memory for " + config.chat().ttl()
                + " without activity and do not survive a restart"));
    }

    // ---- worker --------------------------------------------------------------------------------------------------

    /** Mutable tallies of one turn. */
    private static final class Tally {
        final List<Step> steps = new ArrayList<>();
        int modelCalls;
        int toolCalls;
        Integer promptTokens;
        Integer completionTokens;
        int toolBytes;
        boolean limited;

        void tokens(ChatResult r) {
            modelCalls++;
            if (r.promptTokens() != null) {
                promptTokens = (promptTokens == null ? 0 : promptTokens) + r.promptTokens();
            }
            if (r.completionTokens() != null) {
                completionTokens = (completionTokens == null ? 0 : completionTokens) + r.completionTokens();
            }
        }
    }

    private void run(String conversationId, String turnId, String subject) {
        Timer.Sample sample = Timer.start(registry);
        Span span = tracer.spanBuilder("aiops.chat.turn").setAttribute("aiops.conversation_id", conversationId)
            .setAttribute("aiops.turn_id", turnId).startSpan();
        MDC.put("conversationId", conversationId);
        Tally tally = new Tally();
        Status status;
        String detail = null;
        String answer = null;
        try (Scope ignored = span.makeCurrent()) {
            Conversation c = store.update(conversationId, conv -> conv.withTurn(turnId,
                t -> t.with(Status.RUNNING, null, null, List.of(), null, null), clock.instant())).orElse(null);
            if (c == null) {
                // Evicted while queued: nothing to answer into.
                span.end();
                MDC.remove("conversationId");
                return;
            }
            Answer a = answer(conversationId, turnId, c, tally);
            status = a.status();
            detail = a.detail();
            answer = a.text();
        } catch (RuntimeException e) {
            Log.errorf("chat turn %s/%s failed: %s at %s", conversationId, turnId, e.getClass().getSimpleName(),
                where(e));
            span.setStatus(StatusCode.ERROR);
            status = Status.FAILED;
            detail = "internal error";
        }
        Status s = status;
        String d = detail;
        String text = answer;
        Instant done = clock.instant();
        store.update(conversationId, conv -> conv.withTurn(turnId, t -> t.with(s, d, s.answered() ? text : null,
            List.copyOf(tally.steps), use(tally), done), done));
        span.setAttribute("aiops.status", s.name());
        span.end();
        sample.stop(registry.timer("aiops_chat_turn_duration_seconds", "status", s.name()));
        registry.counter("aiops_chat_turns_total", "status", s.name()).increment();
        Log.infof("chat turn finished conversationId=%s turnId=%s subject=%s status=%s modelCalls=%d toolCalls=%d "
            + "promptTokens=%s completionTokens=%s policy=%s", conversationId, turnId, subject, s, tally.modelCalls,
            tally.toolCalls, tally.promptTokens, tally.completionTokens, ChatPrompt.POLICY_VERSION);
        MDC.remove("conversationId");
    }

    private record Answer(Status status, String detail, String text) {
    }

    private Answer answer(String conversationId, String turnId, Conversation c, Tally tally) {
        AiopsConfig.Chat cfg = config.chat();
        AnalysisService.Profile profile = AnalysisService.Profile.of(config.model().contextProfile());
        Instant deadline = clock.instant().plus(cfg.turnDeadline());
        List<ChatMessage> messages = history(c, turnId, profile.evidenceBytes() / 2);
        for (int round = 0; round < cfg.maxModelRounds() && !tally.limited; round++) {
            if (clock.instant().isAfter(deadline)) {
                return new Answer(Status.FAILED, "the message took longer than " + cfg.turnDeadline(), null);
            }
            ChatResult r = call(messages, true, tally);
            Answer failed = failure(r);
            if (failed != null) {
                return failed;
            }
            if (!r.wantsTools()) {
                return new Answer(Status.COMPLETED, null, r.content().strip());
            }
            messages.add(ChatMessage.assistant(r.content(), r.toolCalls()));
            for (ToolCall call : r.toolCalls()) {
                messages.add(ChatMessage.tool(call.id(), runTool(conversationId, turnId, call, tally, profile)));
            }
            publish(conversationId, turnId, tally);
        }
        // Budget spent: one last call without tools, so the operator still gets an answer from what was read.
        messages.add(ChatMessage.user(ChatPrompt.BUDGET_SPENT));
        ChatResult last = call(messages, false, tally);
        Answer failed = failure(last);
        if (failed != null) {
            return failed;
        }
        return new Answer(Status.LIMIT_REACHED, "the tool budget for this message was used up", last.content().strip());
    }

    private ChatResult call(List<ChatMessage> messages, boolean withTools, Tally tally) {
        ChatResult r = model.chat(new ChatRequest(List.copyOf(messages), withTools ? tools.specs() : List.of(),
            config.chat().answerMaxTokens()));
        tally.tokens(r);
        if (r.promptTokens() != null) {
            registry.counter("aiops_model_tokens_total", "kind", "prompt").increment(r.promptTokens());
        }
        if (r.completionTokens() != null) {
            registry.counter("aiops_model_tokens_total", "kind", "completion").increment(r.completionTokens());
        }
        return r;
    }

    private static Answer failure(ChatResult r) {
        return switch (r.outcome()) {
            case UNAVAILABLE, TIMEOUT -> new Answer(Status.MODEL_UNAVAILABLE, r.detail(), null);
            case INVALID -> new Answer(Status.MODEL_INVALID_OUTPUT, r.detail(), null);
            default -> null;
        };
    }

    private String runTool(String conversationId, String turnId, ToolCall call, Tally tally,
            AnalysisService.Profile profile) {
        AiopsConfig.Chat cfg = config.chat();
        String tool = tools.known(call.name()) ? call.name() : "unknown";
        long start = System.nanoTime();
        ChatTools.Outcome out;
        if (tally.toolCalls >= cfg.maxToolCalls() || tally.toolBytes >= profile.evidenceBytes()) {
            tally.limited = true;
            out = new ChatTools.Outcome("error: the tool budget for this message is used up; answer from what you have",
                "-", "limit", null);
        } else {
            tally.toolCalls++;
            Span span = tracer.spanBuilder("aiops.chat.tool").setAttribute("aiops.tool", tool).startSpan();
            try (Scope ignored = span.makeCurrent()) {
                out = tools.call(call.name(), call.argumentsJson());
            } finally {
                span.end();
            }
            tally.toolBytes += out.text().length();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        tally.steps.add(new Step(tool, out.target(), out.decision(), out.retrievalStatus(), ms));
        registry.counter("aiops_chat_tool_calls_total", "tool", tool, "decision", out.decision()).increment();
        Log.infof("chat_tool conversationId=%s turnId=%s tool=%s target=%s decision=%s durationMs=%d resultBytes=%d",
            conversationId, turnId, tool, out.target(), out.decision(), ms, out.text().length());
        return out.text();
    }

    /** Shows the steps so far while the turn is still running. */
    private void publish(String conversationId, String turnId, Tally tally) {
        List<Step> steps = List.copyOf(tally.steps);
        store.update(conversationId, conv -> conv.withTurn(turnId,
            t -> t.with(Status.RUNNING, null, null, steps, null, null), clock.instant()));
    }

    /**
     * System policy, then earlier questions and answers (newest kept first within {@code maxChars}), then this
     * question. Tool results of earlier turns are not replayed; the model re-reads what it needs.
     */
    private static List<ChatMessage> history(Conversation c, String turnId, int maxChars) {
        List<ChatMessage> earlier = new ArrayList<>();
        int used = 0;
        Turn current = null;
        List<Turn> turns = c.turns();
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            if (t.turnId().equals(turnId)) {
                current = t;
                continue;
            }
            if (!t.status().answered() || t.answer() == null) {
                continue;
            }
            int size = t.question().length() + t.answer().length();
            if (used + size > maxChars) {
                break;
            }
            used += size;
            earlier.add(0, ChatMessage.assistant(t.answer(), List.of()));
            earlier.add(0, ChatMessage.user(t.question()));
        }
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(ChatPrompt.SYSTEM));
        out.addAll(earlier);
        out.add(ChatMessage.user(current == null ? "" : current.question()));
        return out;
    }

    private Conversation.ModelUse use(Tally t) {
        return t.modelCalls == 0 ? null : new Conversation.ModelUse(model.modelId(), t.modelCalls, t.toolCalls,
            t.promptTokens, t.completionTokens);
    }

    private static String where(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("org.box1.")) {
                return f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        return "unknown";
    }

    private static byte[] bytes() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return b;
    }
}
