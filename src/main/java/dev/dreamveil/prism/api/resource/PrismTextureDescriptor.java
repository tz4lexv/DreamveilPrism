package dev.dreamveil.prism.api.resource;

import java.util.Set;

public record PrismTextureDescriptor(
        PrismTextureExtent extent,
        PrismTextureFormat format,
        Set<PrismTextureUsage> usages,
        int mipLevels) {

    public PrismTextureDescriptor {
        if (extent == null || format == null) {
            throw new IllegalArgumentException("Texture extent and format must not be null");
        }
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Texture usages must not be empty");
        }
        usages = Set.copyOf(usages);
        if (mipLevels < 1) {
            throw new IllegalArgumentException("Texture mipLevels must be >= 1");
        }
    }
}
