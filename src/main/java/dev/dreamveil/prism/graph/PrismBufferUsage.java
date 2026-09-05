package dev.dreamveil.prism.graph;

/** Semantic buffer usages exposed by the Minecraft 26.2 Blaze3D abstraction. */
public enum PrismBufferUsage {
    MAP_READ,
    MAP_WRITE,
    CLIENT_STORAGE_HINT,
    COPY_DST,
    COPY_SRC,
    VERTEX,
    INDEX,
    UNIFORM,
    UNIFORM_TEXEL,
    INDIRECT,
    STORAGE
}
