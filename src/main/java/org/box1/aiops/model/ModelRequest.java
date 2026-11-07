package org.box1.aiops.model;

import com.fasterxml.jackson.databind.JsonNode;

/** One chat completion: fixed system policy, untrusted evidence in the user message, structured output schema. */
public record ModelRequest(String system, String user, JsonNode responseSchema, int maxTokens) {
}
