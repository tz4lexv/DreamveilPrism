package dev.dreamveil.prism.graph;

import java.util.Set;

/** Immutable backend-neutral description of a Prism-owned buffer. */
public record PrismBufferDesc(long sizeBytes, Set<PrismBufferUsage> usages) {
    public PrismBufferDesc {
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("Buffer size must be >= 1 byte");
        }
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Buffer usages must not be empty");
        }
        usages = Set.copyOf(usages);
    }
}
