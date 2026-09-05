package dev.dreamveil.prism.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dreamveil.prism.mixin.RenderTypeAccessor;
import dev.dreamveil.prism.mixin.RenderSetupReplayAccessor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.rendertype.LayeringTransform;

/** Bounded current-frame model stream, tapped during vanilla rendering, never re-animated. */
public final class PrismModelViewCapture {
    public static final int MAX_VERTICES = dev.dreamveil.prism.api.PrismLimits.LOADER.maxCapturedModelVertices();
    public static final int STRIDE = 64;
    private static final int MAX_DRAWS = dev.dreamveil.prism.api.PrismLimits.LOADER.maxCapturedModelDraws();
    private static final IdentityHashMap<Object, String> WORLD_STATES = new IdentityHashMap<>();
    private static final ArrayList<Draw> DRAWS = new ArrayList<>();
    private static ByteBuffer data;
    private static boolean enabled, frameActive;
    private static int count, dropped;
    private PrismModelViewCapture() {}
    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean value) {
        reset(); enabled = value;
        if (!value) data = null;
    }
    public static void reset() { WORLD_STATES.clear(); DRAWS.clear(); frameActive = false; count = dropped = 0; }
    public static void beginFrame() {
        if (!enabled) return;
        reset();
        if (data == null) data = ByteBuffer.allocateDirect(MAX_VERTICES * STRIDE).order(ByteOrder.nativeOrder());
        data.clear(); frameActive = true;
    }
    public static void markWorldState(Object state, String domain) {
        if (enabled && frameActive && WORLD_STATES.size() < MAX_DRAWS * 2) WORLD_STATES.put(state, domain);
    }
    public static Capture begin(ModelFeatureRenderer.Submit<?> submit, VertexConsumer delegate) {
        if (!enabled || !frameActive) return null;
        String domain = WORLD_STATES.get(submit.state());
        if (domain == null) return null;
        var type = submit.renderType();
        if (count >= MAX_VERTICES || DRAWS.size() >= MAX_DRAWS || type.isOutline() || type.hasBlending()
                || type.primitiveTopology() != PrimitiveTopology.QUADS
                || submit.sheetedDecalPose() != null) { dropped++; return null; }
        var setup = ((RenderTypeAccessor)(Object)type).dreamveilPrism$getState();
        var state = (RenderSetupReplayAccessor)(Object)setup;
        if (state.prism$textureTransform() != TextureTransform.DEFAULT_TEXTURING
                || state.prism$layeringTransform() != LayeringTransform.NO_LAYERING) { dropped++; return null; }
        return new Capture(delegate, type, domain, count, submit.sprite());
    }
    public static void finish(Capture capture, boolean success) {
        if (capture == null) return;
        int vertices = count - capture.start;
        if (!success || capture.invalid || vertices == 0 || vertices % 4 != 0) {
            count = capture.start; dropped++; return;
        }
        DRAWS.add(new Draw(capture.type, capture.domain, capture.start, vertices, capture.residentDistanceSquared,
                new Bounds(capture.minX,capture.minY,capture.minZ,capture.maxX,capture.maxY,capture.maxZ)));
    }
    static ByteBuffer seal() {
        frameActive = false;
        if (data == null) return ByteBuffer.allocate(0);
        return data.duplicate().position(0).limit(count * STRIDE);
    }
    static List<Draw> draws() { return List.copyOf(DRAWS); }
    static int dropped() { return dropped; }
    record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        boolean intersects(dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Resolved volume,
                double cameraX,double cameraY,double cameraZ) {
            return volume.intersects(minX+cameraX,minY+cameraY,minZ+cameraZ,maxX+cameraX,maxY+cameraY,maxZ+cameraZ);
        }
    }
    record Draw(RenderType type, String domain, int firstVertex, int vertices, double residentDistanceSquared, Bounds bounds) {
        Draw(RenderType type,String domain,int firstVertex,int vertices,double distance) {
            this(type,domain,firstVertex,vertices,distance,null);
        }
        boolean includedBy(int radius) {
            return residentDistanceSquared < 0 || (radius > 0 && residentDistanceSquared <= (double)radius * radius);
        }
    }

    public static final class Capture implements VertexConsumer {
        private final VertexConsumer delegate;
        private final RenderType type;
        private final String domain;
        private final int start;
        private final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
        private int offset = -1;
        private boolean invalid;
        double residentDistanceSquared = -1;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
        double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
        Capture(VertexConsumer delegate, RenderType type, String domain, int start,
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
            this.delegate = delegate; this.type = type; this.domain = domain; this.start = start;
            this.sprite = sprite;
        }
        private void put(int relative, float value) {
            if (offset >= 0) {
                if (!Float.isFinite(value)) invalid = true;
                data.putFloat(offset + relative, value);
            }
        }
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x,y,z);
            if (count >= MAX_VERTICES) { invalid = true; offset = -1; return this; }
            offset = count++ * STRIDE;
            // Position3, Color4, UV2, Normal3, light2, overlay2 (all float).
            for (int i = 0; i < STRIDE; i += 4) data.putFloat(offset + i, 0);
            put(0,x); put(4,y); put(8,z);
            minX=Math.min(minX,x); minY=Math.min(minY,y); minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x); maxY=Math.max(maxY,y); maxZ=Math.max(maxZ,z);
            put(12,1); put(16,1); put(20,1); put(24,1);
            return this;
        }
        public VertexConsumer setColor(int r,int g,int b,int a) {
            delegate.setColor(r,g,b,a);
            put(12,r/255f); put(16,g/255f); put(20,b/255f); put(24,a/255f); return this;
        }
        public VertexConsumer setColor(int color) {
            return setColor((color >>> 16) & 255,(color >>> 8) & 255,color & 255,(color >>> 24) & 255);
        }
        public VertexConsumer setUv(float u,float v) {
            delegate.setUv(u,v);
            put(28, sprite == null ? u : sprite.getU(u));
            put(32, sprite == null ? v : sprite.getV(v)); return this;
        }
        public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); put(36,x); put(40,y); put(44,z); return this; }
        public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); put(48,u); put(52,v); return this; }
        public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); put(56,u); put(60,v); return this; }
        public VertexConsumer setLineWidth(float value) { delegate.setLineWidth(value); return this; }
    }
}
