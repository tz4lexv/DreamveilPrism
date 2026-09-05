package dev.dreamveil.prism.pack;

/** Pack-owned adaptive resolution policy for resources marked dynamic. */
record PrismDynamicResolutionDefinition(
        boolean enabled,
        double minimumScale,
        double maximumScale,
        double targetMilliseconds,
        int evaluationFrames) {
    static final PrismDynamicResolutionDefinition DISABLED =
            new PrismDynamicResolutionDefinition(false, 1.0, 1.0, 16.667, 30);

    PrismDynamicResolutionDefinition {
        if (!Double.isFinite(minimumScale) || !Double.isFinite(maximumScale)
                || minimumScale <= 0.0 || maximumScale < minimumScale || maximumScale > 1.0) {
            throw new IllegalArgumentException("Dynamic resolution scales must satisfy 0 < min <= max <= 1");
        }
        if (!Double.isFinite(targetMilliseconds) || targetMilliseconds < 1.0 || targetMilliseconds > 1000.0) {
            throw new IllegalArgumentException("Dynamic resolution target must be within 1..1000 ms");
        }
        if (evaluationFrames < 8 || evaluationFrames > 240) {
            throw new IllegalArgumentException("Dynamic resolution evaluationFrames must be within 8..240");
        }
    }
}
