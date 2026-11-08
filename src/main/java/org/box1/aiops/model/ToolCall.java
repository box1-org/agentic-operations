package org.box1.aiops.model;

/** A tool call the model asked for. {@code argumentsJson} is model output: untrusted until validated. */
public record ToolCall(String id, String name, String argumentsJson) {
}
