package dev.dreamveil.prism.api;

import java.util.Set;

/** Immutable capability query surface. Unsupported features must never be assumed. */
public interface PrismCapabilities {
    boolean supports(PrismCapability capability);

    Set<PrismCapability> available();

    default void require(PrismCapability capability) {
        if (!supports(capability)) {
            throw new UnsupportedOperationException("Dreamveil Prism capability is unavailable: " + capability);
        }
    }
}
