package dev.dreamveil.prism.pack;

import java.util.List;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** @deprecated since 0.11; use {@link PrismWorldRenderingPipeline}. */
@Deprecated(forRemoval = true)
public final class PrismScenePipelineRegistry {
    private PrismScenePipelineRegistry() {}
    static RenderPipeline vanillaTemplate(PrismSceneDomain domain) { return PrismWorldRenderingPipeline.vanillaTemplate(domain); }
    static void install(List<PrismCompiledScenePipeline> pipelines) {
        if (pipelines.stream().anyMatch(pipeline -> pipeline.outputs().size() > 1)) {
            throw new IllegalArgumentException("Deprecated registry cannot install scene MRT pipelines");
        }
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var color = minecraft.gameRenderer.mainRenderTarget().getColorTextureView();
        try (var prepared = PrismSceneAttachmentStore.prepare(
                com.mojang.blaze3d.systems.RenderSystem.getDevice(),
                color.getWidth(0), color.getHeight(0), java.util.List.of())) {
            PrismWorldRenderingPipeline.install(
                    "compat", 0L, pipelines, java.util.List.of(), java.util.List.of(), prepared);
        }
    }
    static void clear() { PrismWorldRenderingPipeline.clear(); }
    public static RenderPipeline resolve(ChunkSectionLayer layer, RenderPipeline vanilla) {
        return PrismWorldRenderingPipeline.resolveTerrain(layer, vanilla);
    }
    public static boolean hasActiveScenePipelines() { return !PrismWorldRenderingPipeline.snapshot().activeDomains().isEmpty(); }
}
