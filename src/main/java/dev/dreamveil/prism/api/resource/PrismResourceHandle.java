package dev.dreamveil.prism.api.resource;

/** Opaque logical graph resource handle. It never exposes a native GPU object. */
public sealed interface PrismResourceHandle permits PrismTextureHandle, PrismBufferHandle {
    PrismResourceId id();
}
