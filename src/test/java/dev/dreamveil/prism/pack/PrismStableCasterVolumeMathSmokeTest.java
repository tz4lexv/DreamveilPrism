package dev.dreamveil.prism.pack;

/** Regression gate for alpha.7.2.7 stable caster selection. */
public final class PrismStableCasterVolumeMathSmokeTest {
    private PrismStableCasterVolumeMathSmokeTest() {}

    public static void main(String[] args) {
        // Axis-aligned light basis makes the invariants easy to audit:
        // right=X, up=Y, ray=Z.
        var a = PrismStableCasterVolumeMath.volume(
                1.0, 70.0, 2.0,
                1, 0, 0,
                0, 1, 0,
                0, 0, 1,
                256.0, 511.0, 512.0, 32.0, 16.0);
        var b = PrismStableCasterVolumeMath.volume(
                15.9, 79.9, 15.9,
                1, 0, 0,
                0, 1, 0,
                0, 0, 1,
                256.0, 511.0, 512.0, 32.0, 16.0);
        require(PrismStableCasterVolumeMath.sameKey(a, b),
                "sub-section camera motion changed stable caster key");

        var c = PrismStableCasterVolumeMath.volume(
                16.1, 70.0, 2.0,
                1, 0, 0,
                0, 1, 0,
                0, 0, 1,
                256.0, 511.0, 512.0, 32.0, 16.0);
        require(!PrismStableCasterVolumeMath.sameKey(a, c),
                "crossing a section-sized anchor cell did not change stable caster key");

        // A caster near the visual 256-block edge remains selected by the 32-block guard.
        require(PrismStableCasterVolumeMath.overlaps(
                        a, 270, 64, -8, 286, 80, 8,
                        1, 0, 0, 0, 1, 0, 0, 0, 1),
                "guard-band caster was rejected");

        // A clearly irrelevant lateral caster is rejected.
        require(!PrismStableCasterVolumeMath.overlaps(
                        a, 400, 64, -8, 416, 80, 8,
                        1, 0, 0, 0, 1, 0, 0, 0, 1),
                "far lateral caster was accepted");

        // Depth guard also retains a caster just outside the visual near/far interval.
        require(PrismStableCasterVolumeMath.overlaps(
                        a, 0, 64, 520, 16, 80, 536,
                        1, 0, 0, 0, 1, 0, 0, 0, 1),
                "depth guard did not retain edge caster");

        System.out.println("PRISM_STABLE_CASTER_VOLUME_MATH_SMOKE_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
