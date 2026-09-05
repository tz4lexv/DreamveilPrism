package dev.dreamveil.prism.render;

import java.util.concurrent.atomic.AtomicLong;

import org.joml.Matrix4f;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.api.frame.PrismMatrix4;
import dev.dreamveil.prism.api.frame.PrismVec3;
import dev.dreamveil.prism.bridge.PrismBridgeContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Minecraft 26.2 -> stable Prism Frame API adapter. */
public final class PrismFrameTracker {
    private static final AtomicLong FRAME_INDEX = new AtomicLong();
    private static volatile PrismBridgeContext bridgeContext;
    private static volatile boolean registered;
    private static volatile boolean enabled;

    private PrismFrameTracker() {
    }

    public static long nextFrameIndex() {
        return FRAME_INDEX.get();
    }

    public static synchronized void initialize(PrismBridgeContext context) {
        bridgeContext = context;
        enabled = true;
        if (!registered) {
            LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(PrismFrameTracker::capture);
            registered = true;
        }
        PrismMod.LOGGER.info("Prism Frame API v1 capture enabled");
    }

    public static synchronized void close() {
        enabled = false;
        bridgeContext = null;
        PrismMod.LOGGER.info("Prism Frame API capture disabled");
    }

    private static void capture(LevelRenderContext context) {
        PrismBridgeContext publisher = bridgeContext;
        if (!enabled || publisher == null) {
            return;
        }

        // Minecraft creates debugEntries after Prism's client entrypoint runs.
        // Attach lazily from the render lifecycle once that list exists.
        PrismDebugEntry.tryAttach();

        CameraRenderState camera = context.levelState().cameraRenderState;
        if (camera == null || camera.pos == null || camera.projectionMatrix == null || camera.viewRotationMatrix == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        if (target.width < 1 || target.height < 1) {
            return;
        }

        long frameIndex = FRAME_INDEX.getAndIncrement();
        float deltaSeconds = Math.max(0.0f, minecraft.getFrameTimeNs() / 1_000_000_000.0f);
        float depthFar = Math.max(camera.depthFar, 0.001f);

        publisher.publishFrame(new PrismFrameData(
                frameIndex,
                context.levelState().gameTime,
                deltaSeconds,
                target.width,
                target.height,
                new PrismVec3(camera.pos.x, camera.pos.y, camera.pos.z),
                camera.yRot,
                camera.xRot,
                depthFar,
                copyMatrix(camera.projectionMatrix),
                copyMatrix(camera.viewRotationMatrix),
                RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE <= 0.5));
    }

    private static PrismMatrix4 copyMatrix(Matrix4f matrix) {
        return new PrismMatrix4(matrix.get(new float[16]));
    }
}
