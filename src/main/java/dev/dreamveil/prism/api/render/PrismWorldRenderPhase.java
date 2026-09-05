package dev.dreamveil.prism.api.render;

/** Stable high-level phases of Minecraft's world drawing lifecycle exposed by Prism. */
public enum PrismWorldRenderPhase {
    INACTIVE,
    START_MAIN,
    OPAQUE_TERRAIN_COMPLETE,
    SOLID_FEATURES_COMPLETE,
    BEFORE_TRANSLUCENT_TERRAIN,
    TRANSLUCENT_TERRAIN_COMPLETE,
    TRANSLUCENT_FEATURES_COMPLETE,
    END_MAIN
}
