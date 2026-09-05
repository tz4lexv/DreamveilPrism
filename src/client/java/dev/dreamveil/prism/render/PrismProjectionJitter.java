package dev.dreamveil.prism.render;

import org.joml.Matrix4f;

import dev.dreamveil.prism.api.Prism;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Pack-opt-in Halton projection jitter applied before Minecraft builds its level frame graph. */
public final class PrismProjectionJitter {
    private static volatile boolean enabled;

    private PrismProjectionJitter() {}

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static Matrix4f apply(CameraRenderState camera, int width, int height) {
        if (!enabled || camera == null || camera.projectionMatrix == null || width < 1 || height < 1) {
            return null;
        }
        Matrix4f original = new Matrix4f(camera.projectionMatrix);
        double[] jitter = Prism.api().temporal().jitter(PrismFrameTracker.nextFrameIndex());
        camera.projectionMatrix.m20(camera.projectionMatrix.m20() - (float) (2.0 * jitter[0] / width));
        camera.projectionMatrix.m21(camera.projectionMatrix.m21() - (float) (2.0 * jitter[1] / height));
        return original;
    }
}
