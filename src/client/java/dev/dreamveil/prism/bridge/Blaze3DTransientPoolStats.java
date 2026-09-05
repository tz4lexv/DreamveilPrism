package dev.dreamveil.prism.bridge;

public record Blaze3DTransientPoolStats(
        long createdTextures,
        long closedTextures,
        long createdTextureViews,
        long closedTextureViews,
        long createdBuffers,
        long closedBuffers,
        long reuseHits,
        int cachedResources,
        int activeResources) {
}
