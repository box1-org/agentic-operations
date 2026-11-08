package org.box1.aiops.model;

import java.util.List;

/** One chat completion (D-016). {@code tools} may be empty, in which case the model must answer in text. */
public record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, int maxTokens) {
}
