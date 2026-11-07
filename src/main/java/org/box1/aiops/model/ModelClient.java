package org.box1.aiops.model;

/** The replaceable inference layer (D-008). Implementations never offer tools to the model. */
public interface ModelClient {

    String modelId();

    ModelResult complete(ModelRequest request);
}
