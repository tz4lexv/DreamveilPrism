package dev.dreamveil.prism.runtime.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import dev.dreamveil.prism.api.PrismCapabilities;
import dev.dreamveil.prism.api.PrismCapability;

public final class PrismCapabilitiesImpl implements PrismCapabilities {
    private final Set<PrismCapability> capabilities;

    public PrismCapabilitiesImpl(Set<PrismCapability> capabilities) {
        EnumSet<PrismCapability> copy = capabilities == null || capabilities.isEmpty()
                ? EnumSet.noneOf(PrismCapability.class)
                : EnumSet.copyOf(capabilities);
        this.capabilities = Collections.unmodifiableSet(copy);
    }

    @Override
    public boolean supports(PrismCapability capability) {
        return capabilities.contains(capability);
    }

    @Override
    public Set<PrismCapability> available() {
        return capabilities;
    }
}
