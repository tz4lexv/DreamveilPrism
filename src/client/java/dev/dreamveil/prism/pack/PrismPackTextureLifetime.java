package dev.dreamveil.prism.pack;

enum PrismPackTextureLifetime {
    TRANSIENT,
    HISTORY,
    /** Persistent for one Minecraft frame so scene draws can feed the final pack graph. */
    SCENE
}
