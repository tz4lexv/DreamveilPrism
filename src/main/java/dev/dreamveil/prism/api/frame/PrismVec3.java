package dev.dreamveil.prism.api.frame;

/** Double-precision world-space vector for camera positions and large worlds. */
public record PrismVec3(double x, double y, double z) {
    public static final PrismVec3 ZERO = new PrismVec3(0.0, 0.0, 0.0);
}
