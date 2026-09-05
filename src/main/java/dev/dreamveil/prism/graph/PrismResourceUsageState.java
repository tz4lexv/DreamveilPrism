package dev.dreamveil.prism.graph;

/** Backend-neutral resource state used by Prism's synchronization validation plan. */
public enum PrismResourceUsageState {
    UNDEFINED,
    SAMPLED_READ,
    COLOR_ATTACHMENT_WRITE,
    COLOR_ATTACHMENT_READ_WRITE,
    DEPTH_ATTACHMENT_WRITE,
    DEPTH_ATTACHMENT_READ_WRITE,
    STORAGE_IMAGE_READ,
    STORAGE_IMAGE_WRITE,
    STORAGE_IMAGE_READ_WRITE,
    BUFFER_READ,
    BUFFER_WRITE,
    BUFFER_READ_WRITE
}
