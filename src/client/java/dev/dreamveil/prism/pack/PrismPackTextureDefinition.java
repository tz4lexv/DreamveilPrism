package dev.dreamveil.prism.pack;

import dev.dreamveil.prism.graph.PrismTextureDesc;

record PrismPackTextureDefinition(
        String id,
        PrismTextureDesc descriptor,
        PrismPackTextureLifetime lifetime) {
    PrismPackTextureDefinition {
        if (id == null || id.isBlank() || descriptor == null || lifetime == null) {
            throw new IllegalArgumentException("Pack texture id/descriptor/lifetime must not be null or blank");
        }
    }

    boolean history() {
        return lifetime == PrismPackTextureLifetime.HISTORY;
    }

    boolean scene() {
        return lifetime == PrismPackTextureLifetime.SCENE;
    }
}
