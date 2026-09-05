package dev.dreamveil.prism.pack;

/** Pure adaptive-scale regression test; no Minecraft or GPU device is created. */
public final class PrismDynamicResolutionSmokeTest {
    private PrismDynamicResolutionSmokeTest() {}

    public static void main(String[] args) {
        PrismDynamicResolutionController controller = new PrismDynamicResolutionController();
        controller.configure(new PrismDynamicResolutionDefinition(true, 0.5, 1.0, 16.667, 8));
        require(controller.consumeChanged(), "initial allocation invalidation");
        require(!controller.consumeChanged(), "change latch clears");

        for (int i = 0; i < 8; i++) controller.recordFrame(40_000_000L);
        double reduced = controller.scale();
        require(reduced < 1.0 && reduced >= 0.94, "bounded downscale step");
        require(controller.consumeChanged(), "downscale change latch");

        for (int i = 0; i < 8; i++) controller.recordFrame(8_000_000L);
        double raised = controller.scale();
        require(raised > reduced && raised - reduced <= 0.051, "bounded upscale step");
        require(controller.consumeChanged(), "upscale change latch");

        for (int i = 0; i < 8; i++) controller.recordFrame(16_500_000L);
        require(controller.scale() == raised && !controller.consumeChanged(), "hysteresis band");

        controller.reset();
        require(controller.scale() == 1.0, "reset to native pack scale");
        System.out.println("PRISM_DYNAMIC_RESOLUTION_SMOKE_OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Dynamic-resolution contract failed: " + label);
    }
}
