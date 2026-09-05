package dev.dreamveil.prism.api.resource;

public record PrismBufferHandle(PrismResourceId id) implements PrismResourceHandle {
    public PrismBufferHandle {
        if (id == null) {
            throw new IllegalArgumentException("Buffer resource id must not be null");
        }
    }
}
