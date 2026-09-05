package dev.dreamveil.prism.api.graphics;

/** Backend-neutral graphics features exposed to creators. Unsupported features must not be assumed. */
public enum PrismGraphicsFeature {
    GRAPHICS_PIPELINES,
    OFFSCREEN_RENDER_TARGETS,
    DEPTH_ONLY_RENDER_TARGETS,
    MULTIPLE_RENDER_TARGETS,
    MIPMAPPED_TEXTURES,
    INDIRECT_DRAWS,
    MULTI_DRAW_INDIRECT,
    GPU_TIMESTAMP_QUERIES,
    RESOURCE_VIEWS,
    EXPLICIT_ATTACHMENT_LOAD_STORE,
    CUBEMAP_TEXTURES,
    TEXTURE_ARRAYS,
    TEXTURE_3D,
    COMPARISON_SAMPLERS,
    COMPUTE_PIPELINES,
    STORAGE_BUFFERS,
    STORAGE_IMAGES,
    AUXILIARY_SCENE_VIEWS
}
