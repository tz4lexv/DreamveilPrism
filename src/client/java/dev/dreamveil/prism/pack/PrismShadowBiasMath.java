package dev.dreamveil.prism.pack;

/** Shared, backend-neutral bias policy for reversed-Z terrain shadow maps. */
final class PrismShadowBiasMath {
    static final float CONSTANT_DEPTH_BIAS = 0.000025f;
    static final float SLOPE_DEPTH_BIAS = 0.35f;
    static final float MAX_DEPTH_BIAS = 0.00030f;
    static final float RECEIVER_COMPARE_BIAS = 0.00002f;
    static final float MAX_RECEIVER_PLANE_CORRECTION = 0.00035f;

    private PrismShadowBiasMath() {}

    static float casterBias(float depthSlope) {
        if (!Float.isFinite(depthSlope) || depthSlope < 0.0f) {
            throw new IllegalArgumentException("shadow depth slope must be finite and non-negative");
        }
        return Math.min(MAX_DEPTH_BIAS,
                CONSTANT_DEPTH_BIAS + depthSlope * SLOPE_DEPTH_BIAS);
    }
}
