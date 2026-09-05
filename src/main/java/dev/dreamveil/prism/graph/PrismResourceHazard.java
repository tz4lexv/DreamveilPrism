package dev.dreamveil.prism.graph;

/** Ordering hazard between two consecutive uses of the same logical resource. */
public enum PrismResourceHazard {
    NONE,
    READ_AFTER_WRITE,
    WRITE_AFTER_READ,
    WRITE_AFTER_WRITE
}
