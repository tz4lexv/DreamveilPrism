package dev.dreamveil.prism.pack;

/** Receiver contract test: UBO, reversed-Z compare, motion-stable PCF and retained probe mapping. */
public final class PrismShadowReceiverSmokeTest {
    private PrismShadowReceiverSmokeTest() {}

    public static void main(String[] args) {
        require(PrismShadowReceiverMath.CASCADE_COUNT == 2, "receiver must expose two stabilized cascades");
        require(PrismShadowReceiverMath.UNIFORM_BYTES == 208, "std140 cascaded receiver UBO size");
        require(!PrismShadowReceiverMath.hasMainReceiver(0.0f, 0.000001f), "reversed-Z clear is not a receiver");
        require(PrismShadowReceiverMath.hasMainReceiver(0.25f, 0.000001f), "non-clear depth is a receiver");

        require(PrismShadowReceiverMath.isShadowedReversedZ(0.80f, 0.50f, 0.00002f),
                "closer caster must shadow farther receiver in reversed-Z");
        require(!PrismShadowReceiverMath.isShadowedReversedZ(0.50f, 0.80f, 0.00002f),
                "farther caster must not shadow closer receiver");
        require(!PrismShadowReceiverMath.isShadowedReversedZ(0.50001f, 0.50f, 0.00002f),
                "receiver compare bias must reject near-equal self-shadow");
        require(!PrismShadowReceiverMath.isShadowedReversedZ(Float.NaN, 0.5f, 0.0f),
                "non-finite depth must fail closed");

        require(close(PrismShadowBiasMath.casterBias(0.0f), 0.000025f),
                "flat receivers need a non-zero constant acne guard");
        require(close(PrismShadowBiasMath.casterBias(0.0002f), 0.000095f),
                "PCF needs slope bias covering neighboring depth texels");
        require(close(PrismShadowBiasMath.casterBias(1.0f), 0.0003f),
                "grazing-angle bias must remain capped");

        PrismShadowReceiverMath.DepthGradient gradient =
                PrismShadowReceiverMath.receiverPlaneDepthGradient(
                        2.0f, 0.0f, 0.0f, 4.0f,
                        0.6f, 0.8f);
        require(close(gradient.u(), 0.3f) && close(gradient.v(), 0.2f),
                "receiver-plane gradient must recover the planar depth equation");
        require(close(PrismShadowReceiverMath.receiverDepthAtTap(
                        0.5f, gradient, 0.001f, 0.002f,
                        PrismShadowBiasMath.MAX_RECEIVER_PLANE_CORRECTION),
                0.50035f),
                "receiver-plane correction must be capped to avoid edge light leaks");
        PrismShadowReceiverMath.DepthGradient singular =
                PrismShadowReceiverMath.receiverPlaneDepthGradient(
                        1.0f, 2.0f, 2.0f, 4.0f,
                        0.1f, 0.2f);
        require(close(singular.u(), 0.0f) && close(singular.v(), 0.0f),
                "singular receiver-plane derivatives must fall back safely");

        require(PrismShadowReceiverMath.PCF_TAPS_2X2 == 4, "manual 2x2 PCF must use four shadow taps");
        require(close(PrismShadowReceiverMath.bilinearPcf2x2(0f, 0f, 0f, 0f, 0.25f, 0.75f), 0f),
                "fully lit PCF footprint must stay lit");
        require(close(PrismShadowReceiverMath.bilinearPcf2x2(1f, 1f, 1f, 1f, 0.25f, 0.75f), 1f),
                "fully shadowed PCF footprint must stay shadowed");
        require(close(PrismShadowReceiverMath.bilinearPcf2x2(1f, 0f, 0f, 1f, 0.5f, 0.5f), 0.5f),
                "checkerboard PCF footprint must bilinearly resolve to half coverage at center");
        require(close(PrismShadowReceiverMath.bilinearPcf2x2(0f, 1f, 0f, 1f, 0.25f, 0.75f), 0.25f),
                "PCF coverage must follow sub-texel X phase rather than fixed 25 percent weights");

        require(PrismShadowReceiverMath.PCF_TAPS_3X3 == 9, "quadratic 3x3 PCF must use nine shadow taps");
        assertUnitWeightSum(0.0f);
        assertUnitWeightSum(0.25f);
        assertUnitWeightSum(0.5f);
        assertUnitWeightSum(0.75f);
        assertUnitWeightSum(1.0f);

        require(PrismShadowQuality.parse(null) == PrismShadowQuality.BALANCED,
                "missing quality setting must choose the safe balanced default");
        require(PrismShadowQuality.parse("quality") == PrismShadowQuality.QUALITY,
                "quality profile must enable the dual-cascade path");
        require(PrismShadowQuality.parse("unexpected") == PrismShadowQuality.BALANCED,
                "unknown quality values must fail safely to balanced");

        require(close(PrismShadowReceiverMath.nearCascadeWeight(0.01f, 0.02f, 0.08f), 0.0f),
                "near cascade must be disabled at its unsupported edge");
        require(close(PrismShadowReceiverMath.nearCascadeWeight(0.08f, 0.02f, 0.08f), 1.0f),
                "near cascade must own its stable interior");
        float middleWeight = PrismShadowReceiverMath.nearCascadeWeight(0.05f, 0.02f, 0.08f);
        require(middleWeight > 0.0f && middleWeight < 1.0f,
                "cascade transition must blend rather than switch abruptly");

        require(PrismShadowReceiverMath.PROBE_PANEL_COUNT == 4, "truth probe must have exactly four panels");
        require(!PrismShadowReceiverMath.probeFlipsDepthY(0) && !PrismShadowReceiverMath.probeFlipsNdcY(0),
                "Q0 must be raw depth Y + raw NDC Y");
        require(PrismShadowReceiverMath.probeFlipsDepthY(1) && !PrismShadowReceiverMath.probeFlipsNdcY(1),
                "Q1 must flip depth Y only");
        require(!PrismShadowReceiverMath.probeFlipsDepthY(2) && PrismShadowReceiverMath.probeFlipsNdcY(2),
                "Q2 must flip NDC Y only");
        require(PrismShadowReceiverMath.probeFlipsDepthY(3) && PrismShadowReceiverMath.probeFlipsNdcY(3),
                "Q3 must flip both Y conventions");

        boolean rejected = false;
        try {
            PrismShadowReceiverMath.probeFlipsDepthY(4);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "truth probe must reject invalid panel ids");

        System.out.println("PRISM_SHADOW_RECEIVER_SMOKE_OK");
    }

    private static void assertUnitWeightSum(float phase) {
        float[] weights = PrismShadowReceiverMath.quadraticPcfWeights(phase);
        require(weights.length == 3, "quadratic PCF must return three axis weights");
        require(close(weights[0] + weights[1] + weights[2], 1.0f),
                "quadratic PCF weights must conserve coverage");
        require(weights[0] >= 0.0f && weights[1] >= 0.0f && weights[2] >= 0.0f,
                "quadratic PCF weights must remain non-negative");
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) <= 1.0e-6f;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("PrismShadowReceiver smoke failed: " + label);
    }
}
