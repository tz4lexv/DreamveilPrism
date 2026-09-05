package dev.dreamveil.prism.api.shadow;

import java.util.Objects;

/** Accepted caster plus the CSM cascade bit-mask it intersects. */
public record PrismShadowVisibleCandidate(PrismShadowCandidate candidate, int cascadeMask) {
    public PrismShadowVisibleCandidate {
        Objects.requireNonNull(candidate, "candidate");
        if (cascadeMask <= 0) {
            throw new IllegalArgumentException("Visible shadow candidates require a non-zero cascade mask");
        }
    }

    public boolean intersectsCascade(int cascadeIndex) {
        if (cascadeIndex < 0 || cascadeIndex >= Integer.SIZE) {
            throw new IndexOutOfBoundsException("cascadeIndex must be in [0, 31]");
        }
        return (cascadeMask & (1 << cascadeIndex)) != 0;
    }
}
