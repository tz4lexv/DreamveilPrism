package dev.dreamveil.prism.pack;

import java.util.Objects;

import com.mojang.blaze3d.systems.RenderPass;

/** Minecraft 26.2 fullscreen-triangle draw ABI in one audited location. */
final class PrismFullscreenDraw {
    private static final int VERTEX_COUNT = 3;
    private static final int INSTANCE_COUNT = 1;
    private static final int FIRST_VERTEX = 0;
    private static final int FIRST_INSTANCE = 0;

    private PrismFullscreenDraw() {}

    static void draw(RenderPass renderPass) {
        Objects.requireNonNull(renderPass, "renderPass");
        // Minecraft 26.2: draw(vertexCount, instanceCount, firstVertex, firstInstance).
        renderPass.draw(VERTEX_COUNT, INSTANCE_COUNT, FIRST_VERTEX, FIRST_INSTANCE);
    }
}
