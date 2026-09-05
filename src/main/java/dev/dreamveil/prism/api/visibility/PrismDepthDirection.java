package dev.dreamveil.prism.api.visibility;

/** Mapping direction between camera distance and normalized depth-buffer value. */
public enum PrismDepthDirection {
    FORWARD_Z,
    REVERSED_Z;

    /** Depth-buffer clear value representing the farthest possible sample. */
    public double farDepthBufferValue() {
        return this == FORWARD_Z ? 1.0 : 0.0;
    }

    /** Depth-buffer value representing the near side of the normalized depth interval. */
    public double nearDepthBufferValue() {
        return this == FORWARD_Z ? 0.0 : 1.0;
    }

    /** True when candidate is geometrically closer according to this depth direction. */
    public boolean isCloser(double candidate, double reference) {
        if (!Double.isFinite(candidate) || !Double.isFinite(reference)) {
            throw new IllegalArgumentException("Depth comparison values must be finite");
        }
        return this == FORWARD_Z ? candidate < reference : candidate > reference;
    }

    /** Conservative farthest-sample reduction for an HZB/occlusion depth pyramid. */
    public PrismDepthReduction farthestReduction() {
        return this == FORWARD_Z ? PrismDepthReduction.MAX : PrismDepthReduction.MIN;
    }

    /** Nearest-sample reduction, useful for algorithms that explicitly require the closest surface. */
    public PrismDepthReduction nearestReduction() {
        return this == FORWARD_Z ? PrismDepthReduction.MIN : PrismDepthReduction.MAX;
    }
}
