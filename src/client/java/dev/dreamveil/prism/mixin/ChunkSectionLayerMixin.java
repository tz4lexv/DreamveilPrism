package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes vanilla chunk-section draws through the currently committed Prism scene generation. */
@Mixin(ChunkSectionLayer.class)
public abstract class ChunkSectionLayerMixin {
    @Inject(method = "pipeline", at = @At("RETURN"), cancellable = true)
    private void dreamveilPrism$replaceChunkPipeline(CallbackInfoReturnable<RenderPipeline> cir) {
        ChunkSectionLayer layer = (ChunkSectionLayer) (Object) this;
        cir.setReturnValue(PrismWorldRenderingPipeline.resolveTerrain(layer, cir.getReturnValue()));
    }
}
