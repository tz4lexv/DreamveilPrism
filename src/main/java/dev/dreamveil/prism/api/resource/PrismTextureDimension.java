package dev.dreamveil.prism.api.resource;

/**
 * Backend-neutral texture dimensionality.
 *
 * Declaring a dimension does not imply backend support. Creators must still query
 * PrismGraphicsFeature.CUBEMAP_TEXTURES / TEXTURE_ARRAYS / TEXTURE_3D where applicable.
 */
public enum PrismTextureDimension {
    TEXTURE_2D,
    TEXTURE_2D_ARRAY,
    TEXTURE_3D,
    CUBE
}
