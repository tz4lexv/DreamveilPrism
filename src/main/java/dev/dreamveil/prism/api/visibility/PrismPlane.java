package dev.dreamveil.prism.api.visibility;

/** Normalized plane equation ax + by + cz + d >= 0 for points inside a Prism frustum. */
public record PrismPlane(double a, double b, double c, double d) {
    private static final double MIN_NORMAL_LENGTH = 1.0e-12;

    public PrismPlane {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || !Double.isFinite(d)) {
            throw new IllegalArgumentException("Plane coefficients must be finite");
        }
        double length = Math.sqrt(a * a + b * b + c * c);
        if (!(length > MIN_NORMAL_LENGTH)) {
            throw new IllegalArgumentException("Plane normal must be non-zero");
        }
        a /= length;
        b /= length;
        c /= length;
        d /= length;
    }

    public double signedDistance(double x, double y, double z) {
        return a * x + b * y + c * z + d;
    }
}
