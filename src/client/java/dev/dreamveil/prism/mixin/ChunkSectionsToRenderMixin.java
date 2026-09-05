package dev.dreamveil.prism.mixin;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

/** Adds pack-owned MRT views to Minecraft's terrain render pass without replaying the world. */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Unique private ChunkSectionLayerGroup dreamveilPrism$currentGroup;

    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void dreamveilPrism$captureGroup(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        dreamveilPrism$currentGroup = group;
    }

    @Inject(method = "renderGroup", at = @At("RETURN"))
    private void dreamveilPrism$releaseGroup(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        dreamveilPrism$currentGroup = null;
    }

    @Redirect(
            method = "renderGroup",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
    private RenderPass dreamveilPrism$createTerrainPass(
            CommandEncoder encoder,
            Supplier<String> label,
            GpuTextureView color,
            Optional<Vector4fc> colorClear,
            GpuTextureView depth,
            OptionalDouble depthClear) {
        return PrismWorldRenderingPipeline.createTerrainRenderPass(
                encoder, dreamveilPrism$currentGroup, label, color, colorClear, depth, depthClear);
    }
}
