package dev.dreamveil.prism.api.resource;

import java.util.Set;

public record PrismBufferDescriptor(long sizeBytes, Set<PrismBufferUsage> usages) {
    public PrismBufferDescriptor {
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("Buffer size must be >= 1 byte");
        }
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Buffer usages must not be empty");
        }
        usages = Set.copyOf(usages);
    }
}
