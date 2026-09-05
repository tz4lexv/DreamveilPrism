package dev.dreamveil.prism.pack;

/** Pure celestial-light math shared by shadow extraction and smoke tests. */
final class PrismCelestialShadowMath {
    private PrismCelestialShadowMath() {}

    record LightBasis(float rayX, float rayY, float rayZ, float upX, float upY, float upZ) {}

    static float adjustedAngleDegrees(float rawAngleDegrees) {
        return wrapDegrees(rawAngleDegrees + 90.0f);
    }

    static boolean isDayFromRawSunAngle(float rawSunAngleDegrees) {
        return adjustedAngleDegrees(rawSunAngleDegrees) < 180.0f;
    }

    static int cullingBucket(float adjustedAngleDegrees, float stepDegrees) {
        if (!(stepDegrees > 0.0f) || !Float.isFinite(stepDegrees)) {
            throw new IllegalArgumentException("stepDegrees must be finite and positive");
        }
        int bucketCount = Math.max(1, Math.round(360.0f / stepDegrees));
        return Math.floorMod(Math.round(wrapDegrees(adjustedAngleDegrees) / stepDegrees), bucketCount);
    }

    /** Smoothly suppresses extremely long shadows before the sun/moon direction handover. */
    static float horizonStrength(float rayY, float fadeStart, float fullStrengthAt) {
        if (!Float.isFinite(rayY) || !Float.isFinite(fadeStart)
                || !Float.isFinite(fullStrengthAt)
                || fadeStart < 0.0f || fullStrengthAt <= fadeStart || fullStrengthAt > 1.0f) {
            throw new IllegalArgumentException("invalid horizon shadow fade inputs");
        }
        float t = Math.max(0.0f, Math.min(1.0f,
                (Math.abs(rayY) - fadeStart) / (fullStrengthAt - fadeStart)));
        return t * t * (3.0f - 2.0f * t);
    }

    static LightBasis lightBasis(float rawCelestialAngleDegrees, float sunPathRotationDegrees) {
        if (!Float.isFinite(rawCelestialAngleDegrees) || !Float.isFinite(sunPathRotationDegrees)) {
            throw new IllegalArgumentException("Celestial angles must be finite");
        }
        double angle = Math.toRadians(rawCelestialAngleDegrees);
        double path = Math.toRadians(sunPathRotationDegrees);
        float sinAngle = (float) Math.sin(angle);
        float cosAngle = (float) Math.cos(angle);
        float sinPath = (float) Math.sin(path);
        float cosPath = (float) Math.cos(path);

        // Match Iris/Minecraft's JOML transform order exactly. Iris builds
        // Y(-90) * Z(sunPathRotation) * X(celestialAngle) and transforms +Y. Because vectors are
        // column vectors, X is applied first, then Z, then Y. Light rays travel from the celestial
        // body toward the world, so rayDirection is the negative celestial-position vector.
        float rayX = sinAngle;
        float rayY = -cosPath * cosAngle;
        float rayZ = sinPath * cosAngle;

        // Derivative of rayDirection with respect to celestial angle. It is unit-length and
        // orthogonal to rayDirection for every angle/path rotation, so it provides a continuous
        // lookAt up vector even at zenith/nadir.
        float upX = cosAngle;
        float upY = cosPath * sinAngle;
        float upZ = -sinPath * sinAngle;

        return new LightBasis(rayX, rayY, rayZ, upX, upY, upZ);
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0f;
        return wrapped < 0.0f ? wrapped + 360.0f : wrapped;
    }
}
