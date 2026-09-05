package dev.dreamveil.prism.api.resource;

/**
 * Backend-neutral subresource view description introduced in API 1.4.
 * Layered/3D views remain capability gated.
 */
public record PrismResourceViewDescriptor(
        int baseMipLevel,
        int mipLevelCount,
        int baseArrayLayer,
        int arrayLayerCount) {

    public PrismResourceViewDescriptor {
        if (baseMipLevel < 0 || baseArrayLayer < 0) {
            throw new IllegalArgumentException("View base mip/layer must be >= 0");
        }
        if (mipLevelCount < 1 || arrayLayerCount < 1) {
            throw new IllegalArgumentException("View mip/layer counts must be >= 1");
        }
    }

    public static PrismResourceViewDescriptor full2D(int mipLevels) {
        return new PrismResourceViewDescriptor(0, mipLevels, 0, 1);
    }

    public void validateAgainst(int textureMipLevels, int textureArrayLayers) {
        if (textureMipLevels < 1 || textureArrayLayers < 1) {
            throw new IllegalArgumentException("Texture mip/layer counts must be >= 1");
        }
        if ((long) baseMipLevel + mipLevelCount > textureMipLevels) {
            throw new IllegalArgumentException("Texture view mip range exceeds texture descriptor");
        }
        if ((long) baseArrayLayer + arrayLayerCount > textureArrayLayers) {
            throw new IllegalArgumentException("Texture view layer range exceeds texture descriptor");
        }
    }
}
