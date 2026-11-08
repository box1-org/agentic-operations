package org.box1.aiops.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * An Assistant conversation as returned by REST (D-016). Held in memory only; nothing in it is ever executed. Answers
 * are model output and are labelled as such by the console.
 */
public record Conversation(String conversationId, Instant createdAt, Instant updatedAt, List<Turn> turns) {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, LIMIT_REACHED, MODEL_UNAVAILABLE, MODEL_INVALID_OUTPUT, FAILED;

        public boolean finished() {
            return this != QUEUED && this != RUNNING;
        }

        /** Final statuses whose answer text is model output worth showing. */
        public boolean answered() {
            return this == COMPLETED || this == LIMIT_REACHED;
        }
    }

    /**
     * One question and its answer. {@code steps} are the tool reads made for it, in order. {@code statusDetail} is a
     * fixed, service-written reason, never model text.
     */
    public record Turn(String turnId, String question, Status status, String statusDetail, String answer,
            List<Step> steps, ModelUse model, Instant startedAt, Instant completedAt) {

        Turn with(Status s, String detail, String a, List<Step> st, ModelUse m, Instant done) {
            return new Turn(turnId, question, s, detail, a, st, m, startedAt, done);
        }
    }

    /**
     * One tool read. {@code target} is a validated ID (or "invalid"/"-"), never model free text. {@code decision} is
     * ok, invalid_argument, not_found, unknown_tool, limit or error.
     */
    public record Step(String tool, String target, String decision, String retrievalStatus, long durationMs) {
    }

    public record ModelUse(String modelId, int modelCalls, int toolCalls, Integer promptTokens, Integer completionTokens) {
    }

    Conversation withTurn(String turnId, UnaryOperator<Turn> change, Instant now) {
        List<Turn> out = new ArrayList<>(turns.size());
        for (Turn t : turns) {
            out.add(t.turnId().equals(turnId) ? change.apply(t) : t);
        }
        return new Conversation(conversationId, createdAt, now, List.copyOf(out));
    }

    Conversation append(Turn turn, Instant now) {
        List<Turn> out = new ArrayList<>(turns);
        out.add(turn);
        return new Conversation(conversationId, createdAt, now, List.copyOf(out));
    }

    Conversation without(String turnId) {
        return new Conversation(conversationId, createdAt, updatedAt,
            turns.stream().filter(t -> !t.turnId().equals(turnId)).toList());
    }

    Turn last() {
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }
}
