package dev.dreamveil.prism.pack;

public final class PrismCelestialShadowMathSmokeTest {
    private PrismCelestialShadowMathSmokeTest() {}

    public static void main(String[] args) {
        assertNear(0.0f, PrismCelestialShadowMath.adjustedAngleDegrees(-90.0f), "dawn wrap");
        assertNear(180.0f, PrismCelestialShadowMath.adjustedAngleDegrees(90.0f), "night boundary");
        if (!PrismCelestialShadowMath.isDayFromRawSunAngle(-90.0f)) {
            throw new AssertionError("-90 raw sun angle should be day-side after Iris-style +90 adjustment");
        }
        if (PrismCelestialShadowMath.isDayFromRawSunAngle(90.0f)) {
            throw new AssertionError("90 raw sun angle should be night-side after Iris-style +90 adjustment");
        }

        checkBasis(0.0f, 0.0f, 0.0f, -1.0f, 0.0f);
        checkBasis(90.0f, 0.0f, 1.0f, 0.0f, 0.0f);
        checkBasis(180.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        checkBasis(270.0f, 0.0f, -1.0f, 0.0f, 0.0f);

        for (int angle = -360; angle <= 720; angle += 7) {
            var basis = PrismCelestialShadowMath.lightBasis(angle, 35.0f);
            float rayLength = length(basis.rayX(), basis.rayY(), basis.rayZ());
            float upLength = length(basis.upX(), basis.upY(), basis.upZ());
            float dot = basis.rayX() * basis.upX()
                    + basis.rayY() * basis.upY()
                    + basis.rayZ() * basis.upZ();
            assertNear(1.0f, rayLength, "ray norm");
            assertNear(1.0f, upLength, "up norm");
            assertNear(0.0f, dot, "ray/up orthogonality");
        }

        int a = PrismCelestialShadowMath.cullingBucket(10.01f, 0.10f);
        int b = PrismCelestialShadowMath.cullingBucket(10.04f, 0.10f);
        int c = PrismCelestialShadowMath.cullingBucket(10.16f, 0.10f);
        if (a != b || a == c) {
            throw new AssertionError("celestial culling bucket quantization regression");
        }
        int wrapped = PrismCelestialShadowMath.cullingBucket(359.99f, 0.10f);
        if (wrapped != 0) {
            throw new AssertionError("celestial culling bucket must wrap without a duplicate bucket: " + wrapped);
        }

        assertNear(0.0f, PrismCelestialShadowMath.horizonStrength(0.02f, 0.07f, 0.30f),
                "horizon handover must be invisible");
        float transition = PrismCelestialShadowMath.horizonStrength(-0.18f, 0.07f, 0.30f);
        if (!(transition > 0.0f && transition < 1.0f)) {
            throw new AssertionError("horizon transition must be smooth: " + transition);
        }
        assertNear(1.0f, PrismCelestialShadowMath.horizonStrength(-0.5f, 0.07f, 0.30f),
                "elevated light must retain full shadow strength");

        System.out.println("PRISM_CELESTIAL_SHADOW_MATH_SMOKE_OK");
    }

    private static void checkBasis(float angle, float path, float x, float y, float z) {
        var basis = PrismCelestialShadowMath.lightBasis(angle, path);
        assertNear(x, basis.rayX(), "ray x @ " + angle);
        assertNear(y, basis.rayY(), "ray y @ " + angle);
        assertNear(z, basis.rayZ(), "ray z @ " + angle);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void assertNear(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-4f) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }
}
