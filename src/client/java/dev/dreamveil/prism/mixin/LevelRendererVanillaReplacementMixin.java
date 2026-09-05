package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.dreamveil.prism.api.render.PrismVanillaRenderFeature;
import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses late vanilla world passes only while the active, fully compiled pack generation owns
 * the corresponding feature. A failed candidate never reaches this bridge, so Minecraft's pass
 * remains intact and disabling the pack restores it immediately.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVanillaReplacementMixin {
    @Inject(
            method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void dreamveilPrism$replaceSky(
            FrameGraphBuilder graph,
            CameraRenderState camera,
            GpuBufferSlice fog,
            CallbackInfo ci) {
        if (PrismWorldRenderingPipeline.replacesVanillaFeature(PrismVanillaRenderFeature.SKY)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addCloudsPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/CloudStatus;Lnet/minecraft/world/phys/Vec3;JFIFI)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void dreamveilPrism$replaceClouds(
            FrameGraphBuilder graph,
            CloudStatus status,
            Vec3 cameraPosition,
            long gameTime,
            float tickDelta,
            int cloudColor,
            float cloudHeight,
            int cloudRange,
            CallbackInfo ci) {
        if (PrismWorldRenderingPipeline.replacesVanillaFeature(PrismVanillaRenderFeature.CLOUDS)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addWeatherPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void dreamveilPrism$replaceWeather(
            FrameGraphBuilder graph,
            GpuBufferSlice fog,
            CallbackInfo ci) {
        if (PrismWorldRenderingPipeline.replacesVanillaFeature(PrismVanillaRenderFeature.WEATHER)) {
            ci.cancel();
        }
    }
}
