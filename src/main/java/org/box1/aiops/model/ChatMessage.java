package org.box1.aiops.model;

import java.util.List;

/**
 * One message in a chat completion (D-016). {@code role} is system, user, assistant or tool. An assistant message may
 * carry the tool calls it asked for; a tool message answers one of them by {@code toolCallId}.
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls == null ? List.of() : List.copyOf(toolCalls), null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId);
    }
}
