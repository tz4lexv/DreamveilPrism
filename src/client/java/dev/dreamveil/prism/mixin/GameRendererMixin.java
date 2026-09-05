package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.client.PrismClient;
import dev.dreamveil.prism.pack.PrismShadowRenderer;
import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import dev.dreamveil.prism.render.PrismProjectionJitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private GameRenderState gameRenderState;
    @Unique private Matrix4f dreamveilPrism$unjitteredProjection;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void dreamveilPrism$applyProjectionJitter(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraRenderState camera = gameRenderState.levelRenderState.cameraRenderState;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        dreamveilPrism$unjitteredProjection = PrismProjectionJitter.apply(camera, target.width, target.height);
    }

    /**
     * Executes creator post-processing after the complete world renderer, while its color and depth
     * are still valid, but before Minecraft clears depth and submits the hand, screen effects, HUD,
     * debug text, or menus. This is the scene/overlay boundary required by temporal reconstruction:
     * UI pixels must never inherit the terrain G-buffer that happens to be behind them.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void dreamveilPrism$finishWorldBeforeOverlays(DeltaTracker deltaTracker, CallbackInfo ci) {
        PrismWorldRenderingPipeline.finishWorldBeforeOverlays();
    }

    /**
     * Fallback only for alpha.7.2.3. The authoritative receiver now runs from Fabric's
     * BEFORE_TRANSLUCENT_TERRAIN event while world depth is still live. If that event is skipped,
     * this pre-hand hook gives diagnostics a last-resort path without making startup brittle.
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void dreamveilPrism$applyTerrainShadowReceiverBeforeHand(CallbackInfo ci) {
        PrismShadowRenderer.applyTerrainShadowReceiverBeforeItemInHand();
    }

    /**
     * Fallback for frames where vanilla does not enter renderItemInHand (spectator/hidden-hand paths).
     * The renderer internally latches the frame, so this is a no-op if the pre-hand hook already ran.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dreamveilPrism$applyTerrainShadowReceiverFallback(DeltaTracker deltaTracker, CallbackInfo ci) {
        PrismShadowRenderer.applyTerrainShadowReceiverAfterLevelRenderFallback();
        if (dreamveilPrism$unjitteredProjection != null) {
            gameRenderState.levelRenderState.cameraRenderState.projectionMatrix
                    .set(dreamveilPrism$unjitteredProjection);
            dreamveilPrism$unjitteredProjection = null;
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void dreamveilPrism$onRendererClose(CallbackInfo ci) {
        PrismClient.shutdown();
    }
}
