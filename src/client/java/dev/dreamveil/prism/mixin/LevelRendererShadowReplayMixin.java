package dev.dreamveil.prism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;

import dev.dreamveil.prism.pack.PrismShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reuses vanilla 26.2's already-prepared opaque terrain state immediately after its real draw.
 * r9 executes the public RenderPass.Draw batches into Prism-owned shadow.depth instead of issuing
 * a second ChunkSectionsToRender.renderGroup call or relying on RenderSystem output overrides.
 *
 * <p>Prism still does not call LevelRenderer.prepareChunkRenders a second time. Independent
 * light-view section preparation remains a later step after the main-view terrain replay is proven.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererShadowReplayMixin {
    @WrapOperation(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0))
    private void dreamveilPrism$captureAndReplayOpaqueDepth(
            ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group,
            GpuSampler sampler,
            Operation<Void> original) {
        original.call(sections, group, sampler);
        PrismShadowRenderer.capturePreparedOpaqueGroup(sections, group, sampler);
    }

}
