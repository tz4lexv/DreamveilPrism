package dev.dreamveil.prism.api.resource;

public enum PrismTextureFormat {
    R8_UNORM(false, false),
    RG8_UNORM(false, false),
    RGBA8_UNORM(false, false),
    R16_FLOAT(false, false),
    RG16_FLOAT(false, false),
    RGBA16_FLOAT(false, false),
    R32_FLOAT(false, false),
    RG32_FLOAT(false, false),
    RGBA32_FLOAT(false, false),
    RG11B10_FLOAT(false, false),
    D16_UNORM(true, false),
    D24_UNORM_S8_UINT(true, true),
    D32_FLOAT(true, false),
    D32_FLOAT_S8_UINT(true, true);

    private final boolean depth;
    private final boolean stencil;

    PrismTextureFormat(boolean depth, boolean stencil) {
        this.depth = depth;
        this.stencil = stencil;
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
}
