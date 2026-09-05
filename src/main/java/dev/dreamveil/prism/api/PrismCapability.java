package dev.dreamveil.prism.api;

/**
 * Capabilities are queried instead of assuming a concrete graphics API.
 * Unsupported capabilities must remain disabled rather than being emulated implicitly.
 */
public enum PrismCapability {
    /** The active Minecraft GPU backend is the native Vulkan implementation. */
    VULKAN_BACKEND,
    CUSTOM_RENDER_PIPELINES,
    REVERSED_DEPTH,
    TRANSIENT_GPU_RESOURCES,
    RESOURCE_ALIASING,
    GPU_BUFFERS,
    SHADER_PACK_RUNTIME,
    SHADER_HOT_RELOAD,
    FULLSCREEN_SHADER_PASSES,
    SCENE_TERRAIN_PIPELINES,
    NATIVE_PROGRAM_SETS,
    MANIFESTLESS_NATIVE_PACKS,
    LEGACY_PACK_INSPECTION,
    WORLD_RENDER_PIPELINE,
    WORLD_RENDER_PHASE_TRACKING,
    FEATURE_RENDER_MODEL_PIPELINES,
    DYNAMIC_SCENE_PIPELINE_VARIANTS,
    ENTITY_SCENE_PIPELINES,
    BLOCK_ENTITY_SCENE_PIPELINES,
    SEMANTIC_SCENE_RESOURCES,
    MULTIPASS_SHADER_PACKS,
    PACK_FRAME_UNIFORMS,
    PACK_TEMPORAL_HISTORY,
    PACK_TEXTURE_ASSETS,
    SCENE_PROJECTION_JITTER,
    PACK_DYNAMIC_RESOLUTION,
    SHADER_PACK_ARCHIVES,
    LIVE_SHADER_LIBRARY,
    SHADER_PACK_VERSIONING,
    PACK_SETTINGS,
    CREATOR_CPU_PROFILING,
    CREATOR_GPU_PROFILING,
    PIPELINE_CACHE_STATS,
    /** Installed graph passes, resource lifetimes/aliasing and transition hazards are inspectable. */
    RENDER_GRAPH_DIAGNOSTICS,
    RESOURCE_VIEWS,
    ATTACHMENT_LOAD_STORE,
    OFFSCREEN_RENDER_TARGETS,
    DEPTH_ONLY_RENDER_TARGETS,
    MULTIPLE_RENDER_TARGETS,
    /** Pack-authored scene programs can write persistent same-frame G-buffer color attachments. */
    SCENE_GBUFFER_ATTACHMENTS,
    /** A committed pack generation may replace Minecraft sky, cloud and weather passes. */
    VANILLA_SCENE_REPLACEMENT,
    /** Runtime-generated, mipmapped farthest-depth pyramid for the current reversed-Z scene. */
    SCENE_HIERARCHICAL_DEPTH,
    /** Final CPU-deformed opaque/cutout model motion, with explicit coverage and validity. */
    MODEL_DEFORMATION_MOTION,
    MIPMAPPED_TEXTURES,
    INDIRECT_DRAWS,
    GPU_TIMESTAMP_QUERIES,
    SHADOW_CASCADE_HELPERS,
    SHADOW_ATLAS_HELPERS,
    SHADOW_CASTER_CLASSIFICATION,
    SHADOW_VISIBILITY_PLANNING,
    CLIP_CONVENTION_HELPERS,
    FRUSTUM_CULLING_HELPERS,
    TEMPORAL_HELPERS,
    TEMPORAL_INVALIDATION_REASONS,
    CUBEMAP_TEXTURES,
    TEXTURE_ARRAYS,
    TEXTURE_3D,
    COMPARISON_SAMPLERS,
    STORAGE_BUFFERS,
    STORAGE_IMAGES,
    AUXILIARY_SCENE_VIEWS,
    /** Creator-shader replay of resident opaque/cutout terrain, not arbitrary full-scene replay. */
    AUXILIARY_TERRAIN_VIEWS,
    /** Explicit solid/cutout/translucent terrain selection and load/clear composition of auxiliary views. */
    LAYERED_TERRAIN_VIEWS,
    /** Current-frame captured opaque/cutout world Model streams; not independent entity visibility. */
    CAPTURED_MODEL_VIEWS,
    RESIDENT_MODEL_VIEWS,
    MODEL_VISIBILITY_VOLUMES,
    EXPLICIT_PASS_DEPENDENCIES,
    MULTI_DRAW_INDIRECT,
    COMPUTE_PIPELINES,
    DESCRIPTOR_INDEXING,
    ASYNC_COMPUTE,
    RAY_QUERY,
    RAY_TRACING_PIPELINE
}
