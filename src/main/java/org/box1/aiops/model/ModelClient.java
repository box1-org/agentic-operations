package org.box1.aiops.model;

/**
 * The replaceable inference layer (D-008, D-016). {@link #complete} is the incident-analysis call: it never offers tools
 * and refuses a completion that asks for one. {@link #chat} is the Assistant's call: it may offer the read-only chat
 * tools, and the caller decides which tool calls run.
 */
public interface ModelClient {

    String modelId();

    ModelResult complete(ModelRequest request);

    ChatResult chat(ChatRequest request);
}
