package dev.dreamveil.prism.pack;

/** Regression gate for alpha.6 directional shadow texel snapping. */
public final class PrismShadowStabilizationSmokeTest {
    private PrismShadowStabilizationSmokeTest() {
    }

    public static void main(String[] args) {
        double texel = PrismShadowStabilization.worldUnitsPerTexel(512.0, 1024);
        require(close(texel, 0.5), "512/1024 must be 0.5 world units per texel");

        double a = PrismShadowStabilization.snapCoordinate(10.11, texel);
        double b = PrismShadowStabilization.snapCoordinate(10.19, texel);
        require(close(a, 10.0) && close(b, 10.0),
                "sub-texel camera motion changed the snapped shadow center");

        double c = PrismShadowStabilization.snapCoordinate(10.51, texel);
        require(close(c, 10.5), "crossing the texel boundary did not advance one texel");

        for (double coordinate : new double[] {-1234.91, -3.26, -0.01, 0.0, 3.24, 9876.63}) {
            double delta = PrismShadowStabilization.viewTranslation(coordinate, texel);
            require(delta >= -1.0e-12 && delta < texel + 1.0e-12,
                    "floor-aligned view translation escaped [0, texel): " + delta);
        }

        System.out.println("PRISM_SHADOW_STABILIZATION_OK");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0e-12;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
