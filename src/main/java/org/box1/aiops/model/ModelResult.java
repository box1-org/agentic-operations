package org.box1.aiops.model;

/** Outcome of a model call. {@code detail} is a fixed, service-written reason; model text is only in content. */
public record ModelResult(Outcome outcome, String content, String finishReason, Integer promptTokens,
        Integer completionTokens, String detail) {

    public enum Outcome {
        OK, UNAVAILABLE, TIMEOUT, INVALID
    }

    public static ModelResult failure(Outcome outcome, String detail) {
        return new ModelResult(outcome, null, null, null, null, detail);
    }
}
