package org.box1.aiops.model;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the model may call in a chat turn: name, description and a JSON schema for its arguments. */
public record ToolSpec(String name, String description, JsonNode parameters) {
}
