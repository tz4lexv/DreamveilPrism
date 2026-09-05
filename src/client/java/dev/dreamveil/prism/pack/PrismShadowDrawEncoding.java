package dev.dreamveil.prism.pack;

/** Pure integer/offset math shared by the alpha.6 independent terrain shadow path and smoke tests. */
final class PrismShadowDrawEncoding {
    private static final int XZ_BITS = 7;
    private static final int Y_BITS = 8;
    private static final int XZ_MASK = (1 << XZ_BITS) - 1;
    private static final int Y_MASK = (1 << Y_BITS) - 1;
    private static final int XZ_BIAS = 64;
    private static final int Y_BIAS = 128;

    private PrismShadowDrawEncoding() {
    }

    static int packRelativeSection(int dx, int dy, int dz) {
        requireRange("dx", dx, -XZ_BIAS, XZ_BIAS - 1);
        requireRange("dy", dy, -Y_BIAS, Y_BIAS - 1);
        requireRange("dz", dz, -XZ_BIAS, XZ_BIAS - 1);
        return (dx + XZ_BIAS)
                | ((dy + Y_BIAS) << XZ_BITS)
                | ((dz + XZ_BIAS) << (XZ_BITS + Y_BITS));
    }

    static int relativeX(int packed) {
        return (packed & XZ_MASK) - XZ_BIAS;
    }

    static int relativeY(int packed) {
        return ((packed >>> XZ_BITS) & Y_MASK) - Y_BIAS;
    }

    static int relativeZ(int packed) {
        return ((packed >>> (XZ_BITS + Y_BITS)) & XZ_MASK) - XZ_BIAS;
    }

    static int baseVertex(long vertexBufferOffset, int vertexSize) {
        return elementOffset("vertexBufferOffset", vertexBufferOffset, vertexSize);
    }

    static int firstIndex(long indexBufferOffset, int indexBytes) {
        return elementOffset("indexBufferOffset", indexBufferOffset, indexBytes);
    }

    private static int elementOffset(String name, long byteOffset, int elementBytes) {
        if (byteOffset < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        if (elementBytes <= 0) {
            throw new IllegalArgumentException("element size must be > 0");
        }
        if (byteOffset % elementBytes != 0L) {
            throw new IllegalArgumentException(
                    name + "=" + byteOffset + " is not aligned to element size " + elementBytes);
        }
        long elements = byteOffset / elementBytes;
        if (elements > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " exceeds RenderPass int draw ABI");
        }
        return (int) elements;
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + "=" + value + " outside packable range [" + minimum + ", " + maximum + "]");
        }
    }
}
