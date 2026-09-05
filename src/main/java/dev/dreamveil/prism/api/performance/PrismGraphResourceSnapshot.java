package dev.dreamveil.prism.api.performance;

/** Static allocation/lifetime row in the creator render-graph debugger. */
public record PrismGraphResourceSnapshot(
        String name,
        String resourceType,
        boolean imported,
        int firstUsePass,
        int lastUsePass,
        int physicalSlot,
        String description) {
    public PrismGraphResourceSnapshot {
        name = name == null ? "" : name;
        resourceType = resourceType == null ? "UNKNOWN" : resourceType;
        description = description == null ? "" : description;
        if (firstUsePass < -1 || lastUsePass < -1 || physicalSlot < -1) {
            throw new IllegalArgumentException("Graph resource indices must be >= -1");
        }
        if ((firstUsePass == -1) != (lastUsePass == -1)
                || (firstUsePass >= 0 && lastUsePass < firstUsePass)) {
            throw new IllegalArgumentException("Invalid graph resource lifetime");
        }
        if (imported && physicalSlot != -1) {
            throw new IllegalArgumentException("Imported graph resources cannot own a transient slot");
        }
    }
}
