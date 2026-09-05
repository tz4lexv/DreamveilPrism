package dev.dreamveil.prism.graph;

/** Backend-neutral texture formats supported by Prism v0.3. */
public enum PrismTextureFormat {
    R8_UNORM(false, false, 1),
    RG8_UNORM(false, false, 2),
    RGBA8_UNORM(false, false, 4),
    R16_FLOAT(false, false, 2),
    RG16_FLOAT(false, false, 4),
    RGBA16_FLOAT(false, false, 8),
    R32_FLOAT(false, false, 4),
    RG32_FLOAT(false, false, 8),
    RGBA32_FLOAT(false, false, 16),
    RG11B10_FLOAT(false, false, 4),
    D16_UNORM(true, false, 2),
    D24_UNORM_S8_UINT(true, true, 4),
    D32_FLOAT(true, false, 4),
    D32_FLOAT_S8_UINT(true, true, 8);

    private final boolean depth;
    private final boolean stencil;
    private final int bytesPerTexel;

    PrismTextureFormat(boolean depth, boolean stencil, int bytesPerTexel) {
        this.depth = depth;
        this.stencil = stencil;
        this.bytesPerTexel = bytesPerTexel;
    }

    public boolean hasDepthAspect() {
        return depth;
    }

    public boolean hasStencilAspect() {
        return stencil;
    }

    public boolean isColor() {
        return !depth;
    }

    /** Conservative device-memory estimate used by pack admission control. */
    public int bytesPerTexel() {
        return bytesPerTexel;
    }
}
