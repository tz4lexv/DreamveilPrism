package dev.dreamveil.prism.api.shadow;

/** One cascade interval plus optional cross-fade region, in creator-defined view-space units. */
public record PrismCsmCascade(
        int index,
        double nearDistance,
        double farDistance,
        double blendStartDistance) {

    public PrismCsmCascade {
        if (index < 0) throw new IllegalArgumentException("cascade index must be >= 0");
        if (!Double.isFinite(nearDistance) || !Double.isFinite(farDistance) || !Double.isFinite(blendStartDistance)
                || nearDistance < 0.0 || farDistance <= nearDistance
                || blendStartDistance < nearDistance || blendStartDistance > farDistance) {
            throw new IllegalArgumentException("Invalid CSM cascade interval/blend region");
        }
    }
}
