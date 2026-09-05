package dev.dreamveil.prism.api.visibility;

import java.util.Objects;

/**
 * Complete clip/depth convention associated with a camera projection.
 *
 * <p>near/far are geometric camera distances; they are never reinterpreted as
 * minimum/maximum depth-buffer values. This distinction is essential for reversed-Z.</p>
 */
public record PrismClipConvention(
        PrismDepthRange depthRange,
        PrismDepthDirection depthDirection,
        double nearPlane,
        double farPlane,
        PrismProjectionType projectionType) {

    public PrismClipConvention {
        Objects.requireNonNull(depthRange, "depthRange");
        Objects.requireNonNull(depthDirection, "depthDirection");
        Objects.requireNonNull(projectionType, "projectionType");
        if (!Double.isFinite(nearPlane) || !Double.isFinite(farPlane)
                || nearPlane <= 0.0 || farPlane <= nearPlane) {
            throw new IllegalArgumentException("Clip convention requires finite 0 < nearPlane < farPlane");
        }
    }

    /** Explicit ZERO_TO_ONE + REVERSED_Z convention; no backend is inferred implicitly. */
    public static PrismClipConvention reversedZeroToOne(
            double nearPlane,
            double farPlane,
            PrismProjectionType projectionType) {
        return new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE,
                PrismDepthDirection.REVERSED_Z,
                nearPlane,
                farPlane,
                projectionType);
    }

    /** Canonical convention used by Prism algorithms that expect forward-Z frustum math. */
    public PrismClipConvention normalizedForFrustum() {
        return new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE,
                PrismDepthDirection.FORWARD_Z,
                nearPlane,
                farPlane,
                projectionType);
    }

    public boolean sameProjectionVolume(PrismClipConvention other) {
        return other != null
                && Double.compare(nearPlane, other.nearPlane) == 0
                && Double.compare(farPlane, other.farPlane) == 0
                && projectionType == other.projectionType;
    }
}
