package dev.dreamveil.prism.api.visibility;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import dev.dreamveil.prism.api.frame.PrismMatrix4;

/** Clip-convention-aware six-plane view frustum. */
public final class PrismFrustum {
    private final Map<PrismFrustumPlane, PrismPlane> planes;

    private PrismFrustum(Map<PrismFrustumPlane, PrismPlane> planes) {
        this.planes = Map.copyOf(planes);
    }

    /**
     * Extracts planes directly from a view-projection matrix using the declared depth range/direction.
     * This is safe for raw reversed-Z projections; callers do not need to swap near/far coefficients.
     */
    public static PrismFrustum fromViewProjection(
            PrismMatrix4 viewProjection,
            PrismClipConvention convention) {
        Objects.requireNonNull(viewProjection, "viewProjection");
        Objects.requireNonNull(convention, "convention");

        double[] r0 = row(viewProjection, 0);
        double[] r1 = row(viewProjection, 1);
        double[] r2 = row(viewProjection, 2);
        double[] r3 = row(viewProjection, 3);

        EnumMap<PrismFrustumPlane, PrismPlane> result = new EnumMap<>(PrismFrustumPlane.class);
        result.put(PrismFrustumPlane.LEFT, plane(add(r3, r0)));
        result.put(PrismFrustumPlane.RIGHT, plane(subtract(r3, r0)));
        result.put(PrismFrustumPlane.BOTTOM, plane(add(r3, r1)));
        result.put(PrismFrustumPlane.TOP, plane(subtract(r3, r1)));

        double[] lowDepth = convention.depthRange() == PrismDepthRange.ZERO_TO_ONE
                ? r2
                : add(r3, r2);
        double[] highDepth = subtract(r3, r2);

        if (convention.depthDirection() == PrismDepthDirection.FORWARD_Z) {
            result.put(PrismFrustumPlane.NEAR, plane(lowDepth));
            result.put(PrismFrustumPlane.FAR, plane(highDepth));
        } else {
            result.put(PrismFrustumPlane.NEAR, plane(highDepth));
            result.put(PrismFrustumPlane.FAR, plane(lowDepth));
        }
        return new PrismFrustum(result);
    }

    public PrismPlane plane(PrismFrustumPlane plane) {
        return planes.get(Objects.requireNonNull(plane, "plane"));
    }

    public boolean containsPoint(double x, double y, double z) {
        for (PrismPlane plane : planes.values()) {
            if (plane.signedDistance(x, y, z) < 0.0) {
                return false;
            }
        }
        return true;
    }

    /** Conservative AABB/frustum intersection suitable for terrain-section candidate culling. */
    public boolean intersectsAabb(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        for (PrismPlane plane : planes.values()) {
            double x = plane.a() >= 0.0 ? maxX : minX;
            double y = plane.b() >= 0.0 ? maxY : minY;
            double z = plane.c() >= 0.0 ? maxZ : minZ;
            if (plane.signedDistance(x, y, z) < 0.0) {
                return false;
            }
        }
        return true;
    }

    private static void validateBounds(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("AABB bounds must be finite and ordered");
        }
    }

    private static double[] row(PrismMatrix4 matrix, int row) {
        return new double[] {
                matrix.get(0, row), matrix.get(1, row), matrix.get(2, row), matrix.get(3, row)
        };
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]};
    }

    private static PrismPlane plane(double[] coefficients) {
        return new PrismPlane(coefficients[0], coefficients[1], coefficients[2], coefficients[3]);
    }
}
