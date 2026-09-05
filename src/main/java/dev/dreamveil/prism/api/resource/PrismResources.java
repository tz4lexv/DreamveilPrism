package dev.dreamveil.prism.api.resource;

/** Stable host-owned resources that creator graphs may import. */
public final class PrismResources {
    public static final PrismTextureHandle MAIN_COLOR =
            new PrismTextureHandle(PrismResourceId.of("minecraft", "main_color"));
    public static final PrismTextureHandle MAIN_DEPTH =
            new PrismTextureHandle(PrismResourceId.of("minecraft", "main_depth"));
    /** API 1.18: runtime-generated R32_FLOAT reversed-Z farthest-depth mip pyramid. */
    public static final PrismTextureHandle SCENE_HIERARCHICAL_DEPTH =
            new PrismTextureHandle(PrismResourceId.of("minecraft", "scene_hzb"));

    /** API 1.19: RG current-minus-previous UV, B history validity, A model coverage. */
    public static final PrismTextureHandle MODEL_MOTION =
            new PrismTextureHandle(PrismResourceId.of("minecraft", "model_motion"));

    private PrismResources() {}
}
