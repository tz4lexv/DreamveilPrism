package dev.dreamveil.prism.pack;

/** Pure texel-grid math for stabilized directional shadow centers. */
final class PrismShadowStabilization {
    private PrismShadowStabilization() {
    }

    static double worldUnitsPerTexel(double span, int resolution) {
        if (!Double.isFinite(span) || span <= 0.0 || resolution <= 0) {
            throw new IllegalArgumentException("shadow span/resolution must be positive");
        }
        return span / resolution;
    }

    static double snapCoordinate(double coordinate, double worldUnitsPerTexel) {
        if (!Double.isFinite(coordinate)
                || !Double.isFinite(worldUnitsPerTexel)
                || worldUnitsPerTexel <= 0.0) {
            throw new IllegalArgumentException("shadow texel snap inputs must be finite and scale > 0");
        }
        return Math.floor(coordinate / worldUnitsPerTexel) * worldUnitsPerTexel;
    }

    /**
     * View-space translation that converts a camera-relative light coordinate onto a deterministic
     * floor-aligned world-space shadow texel grid. Its magnitude is always in [0, one texel).
     */
    static double viewTranslation(double cameraLightCoordinate, double worldUnitsPerTexel) {
        return cameraLightCoordinate - snapCoordinate(cameraLightCoordinate, worldUnitsPerTexel);
    }
}
