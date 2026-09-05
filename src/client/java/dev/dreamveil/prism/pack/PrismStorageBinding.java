package dev.dreamveil.prism.pack;

/** Explicit Vulkan compute storage descriptor. */
record PrismStorageBinding(String name, String resource, String access, String history) {
    PrismStorageBinding {
        if (name == null || name.isBlank() || resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Storage binding name/resource must not be blank");
        }
        access = access == null || access.isBlank() ? "read_write" : access;
        history = history == null || history.isBlank() ? "current" : history;
    }

    boolean reads() {
        return "read".equals(access) || "read_write".equals(access);
    }

    boolean writes() {
        return "write".equals(access) || "read_write".equals(access);
    }

    boolean previousHistory() {
        return "previous".equals(history);
    }
}
