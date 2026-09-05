package dev.dreamveil.prism.api.resource;

public record PrismTextureHandle(PrismResourceId id) implements PrismResourceHandle {
    public PrismTextureHandle {
        if (id == null) {
            throw new IllegalArgumentException("Texture resource id must not be null");
        }
    }
}
