package dev.dreamveil.prism.graph;

public record PrismResourceRef(String name, PrismResourceAccess access, PrismResourceUsageState requiredState) {
    public PrismResourceRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
        if (access == null) {
            throw new IllegalArgumentException("Resource access must not be null");
        }
    }

    public PrismResourceRef(String name, PrismResourceAccess access) {
        this(name, access, null);
    }
}
