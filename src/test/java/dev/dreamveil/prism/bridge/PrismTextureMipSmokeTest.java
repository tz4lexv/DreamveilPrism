package dev.dreamveil.prism.bridge;

import java.nio.ByteBuffer;

/** Verifies deterministic box-filter mip generation without creating a GPU device. */
public final class PrismTextureMipSmokeTest {
    private PrismTextureMipSmokeTest() {}

    public static void main(String[] args) {
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        put(source, 0, 0, 0, 255);
        put(source, 255, 255, 255, 255);
        put(source, 0, 0, 0, 255);
        put(source, 255, 255, 255, 255);
        source.flip();

        ByteBuffer linear = Blaze3DPackTextureStore.downsampleRgba(source, 2, 2, false);
        require(Byte.toUnsignedInt(linear.get(0)) == 128, "linear RGB average");
        require(Byte.toUnsignedInt(linear.get(3)) == 255, "alpha average");

        ByteBuffer srgb = Blaze3DPackTextureStore.downsampleRgba(source, 2, 2, true);
        int encoded = Byte.toUnsignedInt(srgb.get(0));
        require(encoded >= 187 && encoded <= 189, "sRGB linear-light average");

        ByteBuffer dark = ByteBuffer.allocateDirect(16);
        ByteBuffer bright = ByteBuffer.allocateDirect(16);
        for (int i = 0; i < 4; i++) {
            put(dark, 0, 0, 0, 255);
            put(bright, 255, 255, 255, 255);
        }
        dark.flip();
        bright.flip();
        java.util.List<ByteBuffer> volume = Blaze3DPackTextureStore.downsampleVolumeRgba(
                java.util.List.of(dark, bright), 2, 2, false);
        require(volume.size() == 1 && Byte.toUnsignedInt(volume.getFirst().get(0)) == 128,
                "3D 2x2x2 volume average");
        System.out.println("PRISM_TEXTURE_MIP_SMOKE_OK");
    }

    private static void put(ByteBuffer target, int r, int g, int b, int a) {
        target.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Mip contract failed: " + label);
    }
}
