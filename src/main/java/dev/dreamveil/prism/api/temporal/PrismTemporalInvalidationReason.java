package dev.dreamveil.prism.api.temporal;

/** Why Prism considers temporal history unsafe for the current frame. */
public enum PrismTemporalInvalidationReason {
    FIRST_FRAME,
    RESOLUTION_CHANGED,
    CAMERA_JUMP,
    NON_CONSECUTIVE_FRAME
}
