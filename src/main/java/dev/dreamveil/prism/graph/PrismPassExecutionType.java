package dev.dreamveil.prism.graph;

/** Backend-neutral execution domain used to derive native synchronization scopes. */
public enum PrismPassExecutionType {
    UNKNOWN,
    GRAPHICS,
    COMPUTE,
    TRANSFER
}
