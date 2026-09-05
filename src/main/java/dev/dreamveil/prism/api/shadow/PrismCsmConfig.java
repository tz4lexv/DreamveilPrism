package dev.dreamveil.prism.api.shadow;

/** Creator-controlled CSM split configuration. Prism does not choose the shader's shadow algorithm. */
public record PrismCsmConfig(int cascades, double nearPlane, double farPlane, double lambda) {
    public PrismCsmConfig {
        if (cascades < 1 || cascades > 8) {
            throw new IllegalArgumentException("cascades must be between 1 and 8");
        }
        if (!(Double.isFinite(nearPlane) && Double.isFinite(farPlane) && nearPlane > 0.0 && farPlane > nearPlane)) {
            throw new IllegalArgumentException("CSM planes must be finite with 0 < near < far");
        }
        if (!Double.isFinite(lambda) || lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("CSM lambda must be within [0, 1]");
        }
    }
}
