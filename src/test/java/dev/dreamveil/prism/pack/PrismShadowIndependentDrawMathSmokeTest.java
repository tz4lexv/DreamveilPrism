package dev.dreamveil.prism.pack;

/** Regression gate for the alpha.6 firstInstance section encoding and GPU byte-offset conversion. */
public final class PrismShadowIndependentDrawMathSmokeTest {
    private PrismShadowIndependentDrawMathSmokeTest() {
    }

    public static void main(String[] args) {
        int[][] cases = {
                {0, 0, 0},
                {-1, 4, -2},
                {-64, -128, -64},
                {63, 127, 63},
                {16, -12, 31}
        };
        for (int[] c : cases) {
            int packed = PrismShadowDrawEncoding.packRelativeSection(c[0], c[1], c[2]);
            require(PrismShadowDrawEncoding.relativeX(packed) == c[0], "dx round-trip");
            require(PrismShadowDrawEncoding.relativeY(packed) == c[1], "dy round-trip");
            require(PrismShadowDrawEncoding.relativeZ(packed) == c[2], "dz round-trip");
            require(packed >= 0, "firstInstance must stay non-negative");
        }

        // Exact live values captured from Minecraft 26.2 alpha.6 ABI:
        // vertexBufferOffset=106512, terrain vertex size=28 -> baseVertex=3804.
        require(PrismShadowDrawEncoding.baseVertex(106_512L, 28) == 3_804, "baseVertex ABI");
        require(PrismShadowDrawEncoding.firstIndex(4_096L, 2) == 2_048, "SHORT firstIndex");
        require(PrismShadowDrawEncoding.firstIndex(4_096L, 4) == 1_024, "INT firstIndex");

        expectFailure(() -> PrismShadowDrawEncoding.packRelativeSection(64, 0, 0));
        expectFailure(() -> PrismShadowDrawEncoding.packRelativeSection(0, 128, 0));
        expectFailure(() -> PrismShadowDrawEncoding.baseVertex(3L, 28));
        expectFailure(() -> PrismShadowDrawEncoding.firstIndex(3L, 2));

        System.out.println("PRISM_SHADOW_INDEPENDENT_DRAW_MATH_OK");
    }

    private static void expectFailure(Runnable task) {
        try {
            task.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
