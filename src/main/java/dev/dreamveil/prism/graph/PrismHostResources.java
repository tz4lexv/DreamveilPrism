package dev.dreamveil.prism.graph;

/** Stable semantic names for resources imported from the host renderer. */
public final class PrismHostResources {
    public static final String MAIN_COLOR = "minecraft.main.color";
    public static final String MAIN_DEPTH = "minecraft.main.depth";
    public static final String SCENE_HIERARCHICAL_DEPTH = "minecraft.scene.hierarchical_depth";
    public static final String MODEL_MOTION = "minecraft.scene.model_motion";
    /** Semantic aliases; graph identity/lifetime remain singular. */
    public static final String SCENE_COLOR = MAIN_COLOR;
    public static final String SCENE_DEPTH = MAIN_DEPTH;

    private PrismHostResources() {
    }
}
