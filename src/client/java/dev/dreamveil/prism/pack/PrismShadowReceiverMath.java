package dev.dreamveil.prism.pack;

/** Backend-neutral contracts shared by the cascaded terrain receiver and smoke tests. */
final class PrismShadowReceiverMath {
    record DepthGradient(float u, float v) {}

    static final int MATRIX_FLOATS = 16;
    static final int PARAM_FLOATS = 4;
    static final int CASCADE_COUNT = 2;
    static final int UNIFORM_FLOATS = MATRIX_FLOATS * (1 + CASCADE_COUNT) + PARAM_FLOATS;
    static final int UNIFORM_BYTES = UNIFORM_FLOATS * Float.BYTES;
    static final int PROBE_PANEL_COUNT = 4;
    static final int PCF_TAPS_2X2 = 4;
    static final int PCF_TAPS_3X3 = 9;

    private PrismShadowReceiverMath() {}

    static boolean hasMainReceiver(float reversedDepth, float clearEpsilon) {
        return Float.isFinite(reversedDepth)
                && Float.isFinite(clearEpsilon)
                && clearEpsilon >= 0.0f
                && reversedDepth > clearEpsilon;
    }

    static boolean isShadowedReversedZ(float casterDepth, float receiverDepth, float compareBias) {
        if (!Float.isFinite(casterDepth)
                || !Float.isFinite(receiverDepth)
                || !Float.isFinite(compareBias)
                || compareBias < 0.0f) {
            return false;
        }
        return casterDepth > receiverDepth + compareBias;
    }

    /** Bilinear interpolation of four already-compared 2x2 PCF taps. */
    static float bilinearPcf2x2(float c00, float c10, float c01, float c11, float fx, float fy) {
        if (!finiteUnit(c00) || !finiteUnit(c10) || !finiteUnit(c01) || !finiteUnit(c11)
                || !finiteUnit(fx) || !finiteUnit(fy)) {
            throw new IllegalArgumentException("PCF inputs must be finite and in [0,1]");
        }
        float row0 = c00 + (c10 - c00) * fx;
        float row1 = c01 + (c11 - c01) * fx;
        return row0 + (row1 - row0) * fy;
    }

    /** Continuous quadratic B-spline weights used by the motion-stable 3x3 PCF receiver. */
    static float[] quadraticPcfWeights(float phase) {
        if (!finiteUnit(phase)) {
            throw new IllegalArgumentException("PCF phase must be finite and in [0,1]");
        }
        float oneMinus = 1.0f - phase;
        return new float[] {
                0.5f * oneMinus * oneMinus,
                0.75f - (phase - 0.5f) * (phase - 0.5f),
                0.5f * phase * phase
        };
    }

    /** Solves d(depth)/d(shadowUv) from screen-space derivatives, or returns zero if singular. */
    static DepthGradient receiverPlaneDepthGradient(
            float duDx, float dvDx, float duDy, float dvDy,
            float depthDx, float depthDy) {
        if (!Float.isFinite(duDx) || !Float.isFinite(dvDx)
                || !Float.isFinite(duDy) || !Float.isFinite(dvDy)
                || !Float.isFinite(depthDx) || !Float.isFinite(depthDy)) {
            return new DepthGradient(0.0f, 0.0f);
        }
        float determinant = duDx * dvDy - dvDx * duDy;
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-10f) {
            return new DepthGradient(0.0f, 0.0f);
        }
        float inverse = 1.0f / determinant;
        float u = (depthDx * dvDy - depthDy * dvDx) * inverse;
        float v = (duDx * depthDy - duDy * depthDx) * inverse;
        return Float.isFinite(u) && Float.isFinite(v)
                ? new DepthGradient(u, v)
                : new DepthGradient(0.0f, 0.0f);
    }

    static float receiverDepthAtTap(
            float centerDepth,
            DepthGradient gradient,
            float deltaU,
            float deltaV,
            float maxCorrection) {
        if (!Float.isFinite(centerDepth) || gradient == null
                || !Float.isFinite(gradient.u()) || !Float.isFinite(gradient.v())
                || !Float.isFinite(deltaU) || !Float.isFinite(deltaV)
                || !Float.isFinite(maxCorrection) || maxCorrection < 0.0f) {
            throw new IllegalArgumentException("receiver-plane bias inputs must be finite");
        }
        float correction = gradient.u() * deltaU + gradient.v() * deltaV;
        correction = Math.max(-maxCorrection, Math.min(maxCorrection, correction));
        return centerDepth + correction;
    }

    /** Smooth near-to-far cascade weight from the nearest near-map edge in normalized UV units. */
    static float nearCascadeWeight(float edgeDistance, float blendStart, float fullNearStart) {
        if (!Float.isFinite(edgeDistance) || !Float.isFinite(blendStart)
                || !Float.isFinite(fullNearStart)
                || blendStart < 0.0f || fullNearStart <= blendStart) {
            throw new IllegalArgumentException("cascade blend inputs must be finite and ordered");
        }
        float t = Math.max(0.0f, Math.min(1.0f,
                (edgeDistance - blendStart) / (fullNearStart - blendStart)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static boolean finiteUnit(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    /** Q0/Q2 sample scene depth without Y flip; Q1/Q3 flip scene-depth Y. */
    static boolean probeFlipsDepthY(int panel) {
        checkProbePanel(panel);
        return (panel & 1) != 0;
    }

    /** Q0/Q1 reconstruct NDC Y raw; Q2/Q3 flip NDC Y. */
    static boolean probeFlipsNdcY(int panel) {
        checkProbePanel(panel);
        return (panel & 2) != 0;
    }

    private static void checkProbePanel(int panel) {
        if (panel < 0 || panel >= PROBE_PANEL_COUNT) {
            throw new IllegalArgumentException("receiver truth-probe panel out of range: " + panel);
        }
    }
}
