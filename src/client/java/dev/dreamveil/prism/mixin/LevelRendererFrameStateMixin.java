package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dreamveil.prism.pack.PrismShadowRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the exact world-render model-view at the Minecraft 26.2 LevelRenderer.render entry.
 *
 * <p>Fabric 26.2 exposes the world drawing entry as
 * {@code LevelRenderer.render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState,
 * Matrix4fc, GpuBufferSlice, Vector4f, boolean)}. The injection is intentionally non-fatal so a
 * future signature drift falls back to Prism's extraction-time matrix path instead of crashing
 * Minecraft during mixin application.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameStateMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("RETURN"))
    private void prism$captureResidentModels(CallbackInfo ci) {
        dev.dreamveil.prism.pack.PrismResidentModelViews.capture(
                (LevelRenderer)(Object)this, levelRenderState);
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("HEAD"),
            require = 0)
    private void dreamveilPrism$captureMainWorldRenderState(
            GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker,
            boolean renderOutline,
            CameraRenderState cameraState,
            Matrix4fc modelViewMatrix,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci) {
        PrismShadowRenderer.captureMainWorldRenderState(cameraState, modelViewMatrix);
        dev.dreamveil.prism.pack.PrismModelMotionCapture.beginFrame(cameraState, modelViewMatrix);
        dev.dreamveil.prism.pack.PrismModelViewCapture.beginFrame();
        dev.dreamveil.prism.pack.PrismWorldRenderingPipeline.captureEnvironment(
                cameraState, levelRenderState, fogColor);
    }
}
