package dev.dreamveil.prism.api.visibility;

/** Projection family. Kept explicit so culling/shadow code never infers it from matrix coefficients. */
public enum PrismProjectionType {
    PERSPECTIVE,
    ORTHOGRAPHIC
}
