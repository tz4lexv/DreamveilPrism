package dev.dreamveil.prism.pack;

import java.nio.ByteOrder;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Bounds, forwarding, packed colors, rollback and frame isolation without a Minecraft world/GPU. */
public final class PrismModelViewCaptureTest {
    public static void main(String[] args) {
        var sink = new Sink();
        try {
            PrismModelViewCapture.setEnabled(true);
            PrismModelViewCapture.beginFrame();
            var capture = new PrismModelViewCapture.Capture(sink, null, "entity", 0, null);
            for (int i = 0; i < 4; i++) {
                capture.addVertex(i,2,3).setColor(0x80402010).setUv(.25f,.75f).setNormal(0,1,0).setUv2(240,128).setUv1(7,8);
            }
            PrismModelViewCapture.finish(capture, true);
            var bytes = PrismModelViewCapture.seal().order(ByteOrder.nativeOrder());
            require(bytes.remaining() == 4 * 64, "vertex stride");
            require(bytes.getFloat(4) == 2 && bytes.getFloat(8) == 3, "position");
            require(bytes.getFloat(12) == 64/255f && bytes.getFloat(24) == 128/255f, "ARGB decoding");
            require(bytes.getFloat(28) == .25f && bytes.getFloat(32) == .75f, "UV preservation");
            require(bytes.getFloat(40) == 1 && bytes.getFloat(48) == 240 && bytes.getFloat(56) == 7, "normal/light/overlay");
            require(sink.vertices == 4 && sink.colors == 4 && sink.alpha == 128, "delegate called exactly once");
            require(PrismModelViewCapture.draws().getFirst().domain().equals("entity"), "domain retained");
            var bounds = PrismModelViewCapture.draws().getFirst().bounds();
            require(bounds.minX() == 0 && bounds.maxX() == 3 && bounds.minY() == 2 && bounds.maxZ() == 3,
                    "actual deformed vertex bounds captured without changing vertex ABI");
            PrismModelViewCapture.beginFrame();
            require(PrismModelViewCapture.draws().isEmpty(), "frame draw isolation");
            var partial = new PrismModelViewCapture.Capture(sink, null, "block_entity", 0, null);
            partial.addVertex(1,2,3);
            PrismModelViewCapture.finish(partial, true);
            require(!PrismModelViewCapture.seal().hasRemaining(), "partial quad rolled back");
            PrismModelViewCapture.beginFrame();
            var overflow = new PrismModelViewCapture.Capture(sink, null, "entity", 0, null);
            int before = sink.vertices;
            for (int i = 0; i < PrismModelViewCapture.MAX_VERTICES + 4; i++) overflow.addVertex(0,0,0);
            PrismModelViewCapture.finish(overflow, true);
            require(!PrismModelViewCapture.seal().hasRemaining(), "overflow discards whole submission");
            require(sink.vertices - before == PrismModelViewCapture.MAX_VERTICES + 4, "overflow never truncates vanilla");
            PrismModelViewCapture.beginFrame();
            var invalid = new PrismModelViewCapture.Capture(sink, null, "entity", 0, null);
            for (int i = 0; i < 4; i++) invalid.addVertex(Float.NaN,0,0);
            PrismModelViewCapture.finish(invalid, true);
            require(!PrismModelViewCapture.seal().hasRemaining(), "nonfinite submission rejected");
        } finally { PrismModelViewCapture.setEnabled(false); }
        require(!PrismModelViewCapture.enabled() && PrismModelViewCapture.draws().isEmpty(), "disable releases frame data");
        System.out.println("PRISM_MODEL_VIEW_CAPTURE_PASS");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static final class Sink implements VertexConsumer {
        int vertices,colors,alpha;
        public VertexConsumer addVertex(float x,float y,float z) { vertices++; return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) { colors++; alpha=a; return this; }
        public VertexConsumer setColor(int color) { return setColor((color >>> 16) & 255,(color >>> 8) & 255,color & 255,(color >>> 24) & 255); }
        public VertexConsumer setUv(float u,float v) { return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) { return this; }
        public VertexConsumer setLineWidth(float value) { return this; }
    }
}
