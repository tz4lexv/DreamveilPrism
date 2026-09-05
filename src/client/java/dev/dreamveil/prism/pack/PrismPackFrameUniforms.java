package dev.dreamveil.prism.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.joml.Matrix4f;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.api.frame.PrismMatrix4;
import dev.dreamveil.prism.api.frame.PrismVec3;
import dev.dreamveil.prism.api.temporal.PrismTemporalSnapshot;
import dev.dreamveil.prism.api.temporal.PrismTemporalState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4fc;

/** Shared std140 frame contract for creator-owned fullscreen pipelines. */
final class PrismPackFrameUniforms implements AutoCloseable {
    static final String BINDING_NAME = PrismBuiltinShaderIncludes.FRAME_BLOCK_NAME;
    static final int UNIFORM_BYTES = 800;

    private static volatile EnvironmentFrame environment = EnvironmentFrame.DEFAULT;

    private GpuBuffer buffer;
    private GpuDevice device;
    private long encodedFrame = Long.MIN_VALUE;

    GpuBuffer requireBuffer(
            GpuDevice requiredDevice,
            CommandEncoder encoder,
            boolean packHistoryValid,
            double dynamicScale,
            boolean packResolutionChanged) {
        PrismFrameData current = Prism.api().frames().current().orElseThrow(() ->
                new IllegalStateException("PrismFrame uniforms requested before a world frame was captured"));
        PrismFrameData previous = Prism.api().frames().previous().orElse(current);
        PrismTemporalState temporal = Prism.api().temporal().state();

        if (buffer == null || device != requiredDevice) {
            close();
            device = requiredDevice;
            buffer = requiredDevice.createBuffer(
                    () -> "Dreamveil Prism creator PrismFrame UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES);
        }
        if (encodedFrame != current.frameIndex()) {
            ByteBuffer encoded = encode(
                    current, previous, temporal, packHistoryValid, dynamicScale, packResolutionChanged);
            encoder.writeToBuffer(buffer.slice(), encoded);
            encodedFrame = current.frameIndex();
        }
        return buffer;
    }

    private static ByteBuffer encode(
            PrismFrameData current,
            PrismFrameData previous,
            PrismTemporalState temporal,
            boolean packHistoryValid,
            double dynamicScale,
            boolean packResolutionChanged) {
        Matrix4f projection = matrix(current.projectionMatrix());
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f viewRotation = matrix(current.viewRotationMatrix());
        Matrix4f inverseViewRotation = new Matrix4f(viewRotation).invert();
        Matrix4f viewProjection = new Matrix4f(projection).mul(viewRotation);
        Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();

        Matrix4f previousViewProjection = matrix(previous.projectionMatrix())
                .mul(matrix(previous.viewRotationMatrix()));
        Matrix4f previousInverseViewProjection = new Matrix4f(previousViewProjection).invert();

        PrismTemporalSnapshot snapshot = temporal.snapshot();
        boolean temporalMatchesFrame = snapshot.currentFrameIndex() == current.frameIndex();
        boolean historyValid = temporalMatchesFrame && snapshot.historyValid() && packHistoryValid;
        double[] previousJitter = Prism.api().temporal().jitter(previous.frameIndex());
        Celestial celestial = captureCelestial();

        ByteBuffer out = ByteBuffer.allocateDirect(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
        putMatrix(out, projection);
        putMatrix(out, inverseProjection);
        putMatrix(out, viewRotation);
        putMatrix(out, inverseViewRotation);
        putMatrix(out, viewProjection);
        putMatrix(out, inverseViewProjection);
        putMatrix(out, previousViewProjection);
        putMatrix(out, previousInverseViewProjection);
        putSplitPosition(out, current.cameraPosition());
        putSplitPosition(out, previous.cameraPosition());
        putVec4(out,
                current.renderWidth(), current.renderHeight(),
                1.0f / current.renderWidth(), 1.0f / current.renderHeight());
        float scale = (float) Math.clamp(dynamicScale, 0.0001, 1.0);
        putVec4(out,
                scale, 1.0f / scale,
                Math.max(1.0f, current.renderWidth() * scale),
                Math.max(1.0f, current.renderHeight() * scale));
        putVec4(out,
                exactFloatCounter(current.frameIndex()),
                exactFloatCounter(current.gameTimeTicks()),
                current.deltaSeconds(), current.depthFar());
        putVec4(out, celestial.sunX(), celestial.sunY(), celestial.sunZ(), 0.0f);
        putVec4(out, celestial.moonX(), celestial.moonY(), celestial.moonZ(), 0.0f);
        putVec4(out,
                celestial.sunAngle(), celestial.moonAngle(), celestial.day() ? 1.0f : 0.0f,
                celestial.selectedVertical());
        putVec4(out,
                (float) temporal.jitterX(), (float) temporal.jitterY(),
                (float) previousJitter[0], (float) previousJitter[1]);
        out.putInt(current.reversedDepth() ? 1 : 0);
        out.putInt(historyValid ? 1 : 0);
        out.putInt((temporalMatchesFrame && snapshot.resolutionChanged()) || packResolutionChanged ? 1 : 0);
        out.putInt(temporalMatchesFrame && snapshot.cameraJump() ? 1 : 0);
        EnvironmentFrame capturedEnvironment = environment;
        putVec4(out,
                capturedEnvironment.fogRed(), capturedEnvironment.fogGreen(),
                capturedEnvironment.fogBlue(), capturedEnvironment.fogAlpha());
        putVec4(out,
                capturedEnvironment.environmentalFogStart(), capturedEnvironment.environmentalFogEnd(),
                capturedEnvironment.renderFogStart(), capturedEnvironment.renderFogEnd());
        putVec4(out,
                capturedEnvironment.weatherIntensity(), capturedEnvironment.rainBrightness(),
                capturedEnvironment.starBrightness(), capturedEnvironment.cloudHeight());
        putVec4(out,
                capturedEnvironment.skyRed(), capturedEnvironment.skyGreen(),
                capturedEnvironment.skyBlue(), capturedEnvironment.skyValid() ? 1.0f : 0.0f);
        putVec4(out,
                capturedEnvironment.cloudRed(), capturedEnvironment.cloudGreen(),
                capturedEnvironment.cloudBlue(), capturedEnvironment.cloudValid() ? 1.0f : 0.0f);
        out.putInt(capturedEnvironment.water() ? 1 : 0);
        out.putInt(capturedEnvironment.lava() ? 1 : 0);
        out.putInt(capturedEnvironment.powderSnow() ? 1 : 0);
        out.putInt(capturedEnvironment.atmospheric() ? 1 : 0);
        if (out.position() != UNIFORM_BYTES) {
            throw new IllegalStateException(
                    "PrismFrame ABI encoded " + out.position() + " bytes, expected " + UNIFORM_BYTES);
        }
        out.flip();
        return out;
    }

    static void captureEnvironment(
            CameraRenderState camera,
            LevelRenderState level,
            Vector4fc fogColor) {
        if (camera == null || level == null) {
            environment = EnvironmentFrame.DEFAULT;
            return;
        }
        var fog = camera.fogData;
        var sky = level.skyRenderState;
        var weather = level.weatherRenderState;
        FogType fogType = camera.fogType;
        float[] skyRgb = rgb(level.skyRenderState.skyColor);
        float[] cloudRgb = rgb(level.cloudColor);
        environment = new EnvironmentFrame(
                finite(fogColor == null ? 0.0f : fogColor.x()),
                finite(fogColor == null ? 0.0f : fogColor.y()),
                finite(fogColor == null ? 0.0f : fogColor.z()),
                finite(fogColor == null ? 1.0f : fogColor.w()),
                finite(fog == null ? 0.0f : fog.environmentalStart),
                finite(fog == null ? camera.depthFar : fog.environmentalEnd),
                finite(fog == null ? 0.0f : fog.renderDistanceStart),
                finite(fog == null ? camera.depthFar : fog.renderDistanceEnd),
                finite(weather == null ? 0.0f : weather.intensity),
                finite(sky == null ? 1.0f : sky.rainBrightness),
                finite(sky == null ? 0.0f : sky.starBrightness),
                finite(level.cloudHeight),
                skyRgb[0], skyRgb[1], skyRgb[2], true,
                cloudRgb[0], cloudRgb[1], cloudRgb[2], true,
                fogType == FogType.WATER,
                fogType == FogType.LAVA,
                fogType == FogType.POWDER_SNOW,
                fogType == FogType.ATMOSPHERIC);
    }

    private static float[] rgb(int argb) {
        return new float[] {
                ((argb >>> 16) & 0xff) / 255.0f,
                ((argb >>> 8) & 0xff) / 255.0f,
                (argb & 0xff) / 255.0f};
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static Celestial captureCelestial() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gameRenderer == null) return Celestial.DEFAULT;
        try {
            float tickDelta = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
            float rawSun = probe.getValue(EnvironmentAttributes.SUN_ANGLE, tickDelta);
            float rawMoon = probe.getValue(EnvironmentAttributes.MOON_ANGLE, tickDelta);
            if (!Float.isFinite(rawSun) || !Float.isFinite(rawMoon)) return Celestial.DEFAULT;
            var sun = PrismCelestialShadowMath.lightBasis(rawSun, 0.0f);
            var moon = PrismCelestialShadowMath.lightBasis(rawMoon, 0.0f);
            boolean day = PrismCelestialShadowMath.isDayFromRawSunAngle(rawSun);
            float selectedVertical = Math.abs(day ? sun.rayY() : moon.rayY());
            return new Celestial(
                    -sun.rayX(), -sun.rayY(), -sun.rayZ(),
                    -moon.rayX(), -moon.rayY(), -moon.rayZ(),
                    PrismCelestialShadowMath.adjustedAngleDegrees(rawSun),
                    PrismCelestialShadowMath.adjustedAngleDegrees(rawMoon),
                    day, selectedVertical);
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("PrismFrame celestial attributes unavailable for this frame", exception);
            return Celestial.DEFAULT;
        }
    }

    private static Matrix4f matrix(PrismMatrix4 source) {
        return new Matrix4f().set(source.toArray());
    }

    private static void putMatrix(ByteBuffer target, Matrix4f matrix) {
        for (float value : matrix.get(new float[16])) target.putFloat(value);
    }

    private static void putSplitPosition(ByteBuffer target, PrismVec3 position) {
        float highX = (float) position.x();
        float highY = (float) position.y();
        float highZ = (float) position.z();
        putVec4(target, highX, highY, highZ, 0.0f);
        putVec4(target,
                (float) (position.x() - highX),
                (float) (position.y() - highY),
                (float) (position.z() - highZ),
                0.0f);
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }

    private static float exactFloatCounter(long value) {
        return (float) Math.floorMod(value, 1L << 24);
    }

    @Override
    public void close() {
        if (buffer != null) {
            try {
                buffer.close();
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.warn("PrismFrame uniform buffer cleanup failed", exception);
            }
        }
        buffer = null;
        device = null;
        encodedFrame = Long.MIN_VALUE;
    }

    private record Celestial(
            float sunX, float sunY, float sunZ,
            float moonX, float moonY, float moonZ,
            float sunAngle, float moonAngle,
            boolean day, float selectedVertical) {
        private static final Celestial DEFAULT = new Celestial(
                0.0f, 1.0f, 0.0f,
                0.0f, -1.0f, 0.0f,
                90.0f, 270.0f, true, 1.0f);
    }

    private record EnvironmentFrame(
            float fogRed, float fogGreen, float fogBlue, float fogAlpha,
            float environmentalFogStart, float environmentalFogEnd,
            float renderFogStart, float renderFogEnd,
            float weatherIntensity, float rainBrightness, float starBrightness, float cloudHeight,
            float skyRed, float skyGreen, float skyBlue, boolean skyValid,
            float cloudRed, float cloudGreen, float cloudBlue, boolean cloudValid,
            boolean water, boolean lava, boolean powderSnow, boolean atmospheric) {
        private static final EnvironmentFrame DEFAULT = new EnvironmentFrame(
                0.0f, 0.0f, 0.0f, 1.0f,
                0.0f, 1024.0f, 0.0f, 1024.0f,
                0.0f, 1.0f, 0.0f, 192.0f,
                0.45f, 0.65f, 1.0f, false,
                1.0f, 1.0f, 1.0f, false,
                false, false, false, false);
    }
}
