package dev.dreamveil.prism.graph;

/** Semantic texture usages. The backend maps these to its native usage flags. */
public enum PrismTextureUsage {
    COPY_DST,
    COPY_SRC,
    SAMPLED,
    RENDER_ATTACHMENT,
    STORAGE
}
