package dev.dreamveil.prism.api.shadow;

/** One integer pixel tile in a creator-owned shadow atlas. */
public record PrismShadowAtlasTile(int cascadeIndex, int x, int y, int width, int height) {
    public PrismShadowAtlasTile {
        if (cascadeIndex < 0 || x < 0 || y < 0 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Invalid shadow atlas tile");
        }
    }
}
