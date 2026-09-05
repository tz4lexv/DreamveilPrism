package dev.dreamveil.prism.api.visibility;

import java.util.Objects;

import dev.dreamveil.prism.api.frame.PrismMatrix4;

/**
 * Couples the exact renderer projection with its explicit clip convention.
 * Render code keeps {@link #renderProjection()} untouched; visibility code consumes
 * {@link #cullingProjection()} or an explicitly convention-aware frustum extractor.
 */
public record PrismProjectionDescriptor(
        PrismMatrix4 renderProjection,
        PrismClipConvention clipConvention) {

    public PrismProjectionDescriptor {
        Objects.requireNonNull(renderProjection, "renderProjection");
        Objects.requireNonNull(clipConvention, "clipConvention");
    }

    public PrismClipConvention cullingConvention() {
        return clipConvention.normalizedForFrustum();
    }

    /**
     * Returns a projection with the same geometric frustum but normalized to
     * ZERO_TO_ONE + FORWARD_Z for algorithms whose plane extraction assumes that convention.
     */
    public PrismMatrix4 cullingProjection() {
        return normalizeForFrustumConvention(cullingConvention());
    }

    /**
     * Remaps only clip-space Z. X/Y, perspective divide, geometric near/far distances and
     * the renderer-owned projection remain unchanged. No matrix coefficient is guessed.
     */
    public PrismMatrix4 normalizeForFrustumConvention(PrismClipConvention target) {
        Objects.requireNonNull(target, "target");
        if (!clipConvention.sameProjectionVolume(target)) {
            throw new IllegalArgumentException(
                    "Target frustum convention must preserve near/far distances and projection type");
        }
        if (clipConvention.depthRange() == target.depthRange()
                && clipConvention.depthDirection() == target.depthDirection()) {
            return renderProjection;
        }

        DepthAffine sourceToForward01 = sourceToForwardZeroOne(clipConvention);
        DepthAffine forward01ToTarget = forwardZeroOneToTarget(target);
        double scale = forward01ToTarget.scale * sourceToForward01.scale;
        double offset = forward01ToTarget.scale * sourceToForward01.offset + forward01ToTarget.offset;

        PrismMatrix4 clipDepthRemap = new PrismMatrix4(new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, (float) scale, 0,
                0, 0, (float) offset, 1
        });
        return clipDepthRemap.multiply(renderProjection);
    }

    /** Builds a camera-relative culling frustum from the normalized projection and supplied view matrix. */
    public PrismFrustum cullingFrustum(PrismMatrix4 viewMatrix) {
        Objects.requireNonNull(viewMatrix, "viewMatrix");
        PrismClipConvention convention = cullingConvention();
        return PrismFrustum.fromViewProjection(cullingProjection().multiply(viewMatrix), convention);
    }

    private static DepthAffine sourceToForwardZeroOne(PrismClipConvention source) {
        return switch (source.depthRange()) {
            case ZERO_TO_ONE -> source.depthDirection() == PrismDepthDirection.FORWARD_Z
                    ? new DepthAffine(1.0, 0.0)
                    : new DepthAffine(-1.0, 1.0);
            case NEGATIVE_ONE_TO_ONE -> source.depthDirection() == PrismDepthDirection.FORWARD_Z
                    ? new DepthAffine(0.5, 0.5)
                    : new DepthAffine(-0.5, 0.5);
        };
    }

    private static DepthAffine forwardZeroOneToTarget(PrismClipConvention target) {
        return switch (target.depthRange()) {
            case ZERO_TO_ONE -> target.depthDirection() == PrismDepthDirection.FORWARD_Z
                    ? new DepthAffine(1.0, 0.0)
                    : new DepthAffine(-1.0, 1.0);
            case NEGATIVE_ONE_TO_ONE -> target.depthDirection() == PrismDepthDirection.FORWARD_Z
                    ? new DepthAffine(2.0, -1.0)
                    : new DepthAffine(-2.0, 1.0);
        };
    }

    private record DepthAffine(double scale, double offset) { }
}
