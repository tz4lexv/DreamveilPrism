package dev.dreamveil.prism.api.resource;

/**
 * API 1.4 texture geometry. It is descriptive only; dimensions beyond 2D remain capability-gated.
 */
public record PrismTextureGeometry(
        PrismTextureDimension dimension,
        int depth,
        int arrayLayers) {

    public PrismTextureGeometry {
        if (dimension == null) throw new IllegalArgumentException("Texture dimension must not be null");
        if (depth < 1 || arrayLayers < 1) {
            throw new IllegalArgumentException("Texture depth/layers must be >= 1");
        }
        switch (dimension) {
            case TEXTURE_2D -> {
                if (depth != 1 || arrayLayers != 1) {
                    throw new IllegalArgumentException("2D textures require depth=1 and arrayLayers=1");
                }
            }
            case TEXTURE_2D_ARRAY -> {
                if (depth != 1) throw new IllegalArgumentException("2D arrays require depth=1");
            }
            case TEXTURE_3D -> {
                if (arrayLayers != 1) throw new IllegalArgumentException("3D textures require arrayLayers=1");
            }
            case CUBE -> {
                if (depth != 1 || arrayLayers != 6) {
                    throw new IllegalArgumentException("Cube textures require depth=1 and exactly 6 layers");
                }
            }
        }
    }

    public static PrismTextureGeometry texture2D() {
        return new PrismTextureGeometry(PrismTextureDimension.TEXTURE_2D, 1, 1);
    }

    public static PrismTextureGeometry texture2DArray(int layers) {
        return new PrismTextureGeometry(PrismTextureDimension.TEXTURE_2D_ARRAY, 1, layers);
    }

    public static PrismTextureGeometry texture3D(int depth) {
        return new PrismTextureGeometry(PrismTextureDimension.TEXTURE_3D, depth, 1);
    }

    public static PrismTextureGeometry cube() {
        return new PrismTextureGeometry(PrismTextureDimension.CUBE, 1, 6);
    }
}
