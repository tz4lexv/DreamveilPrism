package dev.dreamveil.prism.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.PrimitiveTopology;
import dev.dreamveil.prism.temporal.PrismVertexMotionHistory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Captures the actual post-animation world-model vertex stream once, during vanilla mesh building. */
public final class PrismModelMotionCapture {
    public static final int MAX_VERTICES = 131072;
    public static final int STRIDE = 36; // current clip vec4, previous clip vec4, validity float
    private static final PrismVertexMotionHistory<Key> HISTORY = new PrismVertexMotionHistory<>(MAX_VERTICES);
    private static final IdentityHashMap<Object, Boolean> WORLD_STATES = new IdentityHashMap<>();
    private static final Map<BaseKey, Integer> ORDINALS = new HashMap<>();
    private static final Matrix4f VP = new Matrix4f();
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    private static ByteBuffer output;
    private static boolean enabled;
    private static boolean frameActive;
    private static Object world;
    private static double cameraX, cameraY, cameraZ;
    private static int width, height;
    private static int capturedVertices;
    private static int validVertices;
    private static long lastFrameNanos;

    private PrismModelMotionCapture() {}

    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean value) {
        if (enabled != value) reset();
        enabled = value;
        if (!value) output = null;
    }
    public static void reset() {
        HISTORY.clear(); WORLD_STATES.clear(); ORDINALS.clear(); ACTIVE.remove();
        frameActive = false; lastFrameNanos = 0; world = null;
        capturedVertices = validVertices = 0;
        if (output != null) output.clear();
    }
    public static void beginFrame(CameraRenderState camera, Matrix4fc view) {
        if (!enabled) return;
        var mc = Minecraft.getInstance();
        var target = mc.gameRenderer.mainRenderTarget();
        long now = System.nanoTime();
        boolean valid = world == mc.level && lastFrameNanos != 0 && now - lastFrameNanos < 500_000_000L
                && width == target.width && height == target.height
                && Math.abs(camera.pos.x - cameraX) < 16
                && Math.abs(camera.pos.y - cameraY) < 16 && Math.abs(camera.pos.z - cameraZ) < 16;
        HISTORY.beginFrame(valid);
        WORLD_STATES.clear(); ORDINALS.clear();
        cameraX = camera.pos.x; cameraY = camera.pos.y; cameraZ = camera.pos.z;
        world = mc.level; width = target.width; height = target.height; lastFrameNanos = now;
        VP.set(camera.projectionMatrix).mul(view);
        if (output == null) output = ByteBuffer.allocateDirect(MAX_VERTICES * 3 / 2 * STRIDE).order(ByteOrder.nativeOrder());
        output.clear();
        capturedVertices = validVertices = 0;
        frameActive = true;
    }
    public static void markWorldState(Object state) {
        if (enabled && frameActive && WORLD_STATES.size() < 4096) WORLD_STATES.put(state, true);
    }
    public static void part(Object part) {
        Capture capture = ACTIVE.get();
        if (capture != null) capture.part = System.identityHashCode(part);
    }
    public static Capture begin(ModelFeatureRenderer.Submit<?> submit, VertexConsumer delegate) {
        if (!enabled || !frameActive || capturedVertices >= MAX_VERTICES || !WORLD_STATES.containsKey(submit.state())
                || submit.renderType().isOutline() || submit.renderType().hasBlending()
                || submit.sheetedDecalPose() != null || submit.sprite() != null
                || submit.renderType().primitiveTopology() != PrimitiveTopology.QUADS) return null;
        Object identity = identity(submit.state());
        if (identity == null) return null;
        BaseKey base = new BaseKey(identity, submit.model(), submit.renderType());
        int ordinal = ORDINALS.merge(base, 1, Integer::sum) - 1;
        Capture capture = new Capture(new Key(base, ordinal), delegate);
        ACTIVE.set(capture);
        return capture;
    }
    private static Object identity(Object state) {
        if (state instanceof EntityRenderState && state instanceof PrismMotionIdentity id) {
            UUID uuid = id.prism$motionId();
            return uuid;
        }
        if (state instanceof BlockEntityRenderState block && block.blockPos != null) {
            return new BlockKey(block.blockPos.asLong(), state.getClass());
        }
        return null;
    }
    public static void finish(Capture capture, boolean success) {
        ACTIVE.remove();
        if (capture == null || !success || capture.overflow || capture.count == 0 || capture.count % 4 != 0) return;
        int count = capture.count;
        if (capturedVertices + count > MAX_VERTICES) return;
        float[] current = Arrays.copyOf(capture.clips, count * 4);
        var previous = HISTORY.record(capture.key, current, capture.topology);
        boolean valid = previous != null;
        float[] old = valid ? previous.clips() : current;
        // Match Minecraft's QUADS -> triangles index order.
        for (int quad = 0; quad < count; quad += 4) {
            write(current, old, quad, valid); write(current, old, quad + 1, valid); write(current, old, quad + 2, valid);
            write(current, old, quad + 2, valid); write(current, old, quad + 3, valid); write(current, old, quad, valid);
        }
        capturedVertices += count;
        if (valid) validVertices += count;
    }
    private static void write(float[] current, float[] previous, int vertex, boolean valid) {
        int offset = vertex * 4;
        for (int i = 0; i < 4; i++) output.putFloat(current[offset + i]);
        for (int i = 0; i < 4; i++) output.putFloat(previous[offset + i]);
        output.putFloat(valid ? 1 : 0);
    }
    public static ByteBuffer data() {
        frameActive = false;
        if (output == null) return ByteBuffer.allocate(0);
        return output.duplicate().flip();
    }
    public static int validVertices() { return validVertices; }
    private record BlockKey(long position, Class<?> type) {}
    private record BaseKey(Object identity, Model<?> model, RenderType type) {}
    private record Key(BaseKey base, int ordinal) {}

    public static final class Capture implements VertexConsumer {
        private final Key key;
        private final VertexConsumer delegate;
        private float[] clips = new float[384];
        private final Vector4f position = new Vector4f();
        private int count;
        private int part;
        private long topology = 0xcbf29ce484222325L;
        private boolean overflow;
        Capture(Key key, VertexConsumer delegate) { this.key = key; this.delegate = delegate; }
        private void hash(int value) { topology = (topology ^ value) * 0x100000001b3L; }
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            if (count >= 16384) { overflow = true; return this; }
            if ((count + 1) * 4 > clips.length) clips = Arrays.copyOf(clips, clips.length * 2);
            VP.transform(position.set(x, y, z, 1));
            if (!position.isFinite()) overflow = true;
            int i = count++ * 4;
            clips[i] = position.x; clips[i+1] = position.y; clips[i+2] = position.z; clips[i+3] = position.w;
            hash(part);
            return this;
        }
        public VertexConsumer setUv(float u, float v) { delegate.setUv(u,v); hash(Float.floatToIntBits(u)); hash(Float.floatToIntBits(v)); return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,a); return this; }
        public VertexConsumer setColor(int c) { delegate.setColor(c); return this; }
        public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); return this; }
        public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); return this; }
        public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); return this; }
        public VertexConsumer setLineWidth(float w) { delegate.setLineWidth(w); return this; }
    }
}
