package dev.dreamveil.prism.api.resource;

/**
 * Stable semantic names for host scene resources currently exposed to creator graphs.
 *
 * <p>Only resources that are actually bindable end-to-end are published here. Additional
 * attachments such as normals, velocity or material data will be added when Prism owns their
 * allocation and lifetime; no placeholder handles are exposed.</p>
 */
public final class PrismSceneResources {
    public static final PrismTextureHandle COLOR = PrismResources.MAIN_COLOR;
    public static final PrismTextureHandle DEPTH = PrismResources.MAIN_DEPTH;
    /** On-demand R32_FLOAT mip pyramid; MIN is the farthest reduction for reversed-Z. */
    public static final PrismTextureHandle HIERARCHICAL_DEPTH = PrismResources.SCENE_HIERARCHICAL_DEPTH;
    /** API 1.19: final CPU-animated model motion; RG velocity, B validity, A coverage. */
    public static final PrismTextureHandle MODEL_MOTION = PrismResources.MODEL_MOTION;

    private PrismSceneResources() {}
}
