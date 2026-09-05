package dev.dreamveil.prism.api.shadow;

import java.util.Objects;

/** One uniquely identified world-space shadow caster candidate. */
public record PrismShadowCandidate(
        long stableId,
        PrismShadowCasterKind kind,
        PrismShadowBounds bounds) {

    public PrismShadowCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bounds, "bounds");
    }
}
