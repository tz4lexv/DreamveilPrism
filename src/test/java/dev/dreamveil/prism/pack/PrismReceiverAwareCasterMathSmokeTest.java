package dev.dreamveil.prism.pack;

public final class PrismReceiverAwareCasterMathSmokeTest {
    private PrismReceiverAwareCasterMathSmokeTest() {}

    public static void main(String[] args) {
        var bounds = PrismReceiverAwareCasterMath.guardAndQuantize(
                12, -21.0, 19.0, -7.0, 25.0, 32.0, 16.0);
        require(bounds.available(), "bounds available");
        require(bounds.minRight() == -64.0 && bounds.maxRight() == 64.0,
                "right guard quantization");
        require(bounds.minUp() == -48.0 && bounds.maxUp() == 64.0,
                "up guard quantization");

        // right=(1,0,0), up=(0,1,0). A caster can be far along light-ray Z and still be valid
        // because directional-light projection preserves transverse XY.
        require(PrismReceiverAwareCasterMath.overlaps(
                bounds, -8, -8, -600, 8, 8, -584,
                1, 0, 0, 0, 1, 0),
                "off-screen caster aligned with receiver footprint must remain");

        require(!PrismReceiverAwareCasterMath.overlaps(
                bounds, 96, -8, -32, 112, 8, -16,
                1, 0, 0, 0, 1, 0),
                "lateral caster outside receiver footprint must be rejected");

        require(PrismReceiverAwareCasterMath.overlaps(
                PrismReceiverAwareCasterMath.Bounds.EMPTY,
                500, 500, 500, 516, 516, 516,
                1, 0, 0, 0, 1, 0),
                "empty receiver bounds must fall back conservatively");

        double negativeAxisMin = PrismReceiverAwareCasterMath.projectAabbMin(
                2, 3, 4, 6, 8, 10, -1, 0, 0);
        double negativeAxisMax = PrismReceiverAwareCasterMath.projectAabbMax(
                2, 3, 4, 6, 8, 10, -1, 0, 0);
        require(negativeAxisMin == -6.0 && negativeAxisMax == -2.0,
                "negative axis interval");

        System.out.println("PRISM_RECEIVER_AWARE_CASTER_MATH_SMOKE_OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
