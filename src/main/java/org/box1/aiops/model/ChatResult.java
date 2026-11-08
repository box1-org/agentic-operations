package org.box1.aiops.model;

import java.util.List;

/**
 * Outcome of a chat completion. OK carries either text content or tool calls (never offered tools are refused as
 * INVALID). {@code detail} is a fixed, service-written reason; model text is only in content.
 */
public record ChatResult(ModelResult.Outcome outcome, String content, List<ToolCall> toolCalls, String finishReason,
        Integer promptTokens, Integer completionTokens, String detail) {

    public static ChatResult failure(ModelResult.Outcome outcome, String detail) {
        return new ChatResult(outcome, null, List.of(), null, null, null, detail);
    }

    public boolean wantsTools() {
        return outcome == ModelResult.Outcome.OK && toolCalls != null && !toolCalls.isEmpty();
    }
}
