package dev.dreamveil.prism.backend;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import dev.dreamveil.prism.api.PrismCapability;

public record PrismBackendInfo(
        String name,
        String implementation,
        Set<PrismCapability> capabilities) {

    public PrismBackendInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backend name must not be blank");
        }
        if (implementation == null || implementation.isBlank()) {
            throw new IllegalArgumentException("Backend implementation must not be blank");
        }
        EnumSet<PrismCapability> copy = capabilities == null || capabilities.isEmpty()
                ? EnumSet.noneOf(PrismCapability.class)
                : EnumSet.copyOf(capabilities);
        capabilities = Collections.unmodifiableSet(copy);
    }

    public boolean supports(PrismCapability capability) {
        return capabilities.contains(capability);
    }
}
