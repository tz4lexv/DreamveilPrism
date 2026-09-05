package dev.dreamveil.prism.backend;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import dev.dreamveil.prism.api.PrismCapability;

/**
 * Audited capability contract for Prism's Minecraft 26.2 Blaze3D bridge.
 *
 * <p>This list contains only paths exercised end-to-end by Prism. Blaze3D exposing a lower-level
 * primitive is not enough to advertise the corresponding creator capability.</p>
 */
public final class PrismMinecraft26_2Capabilities {
    private static final Set<PrismCapability> BASE_PROVEN = Collections.unmodifiableSet(EnumSet.of(
            PrismCapability.CUSTOM_RENDER_PIPELINES,
            PrismCapability.REVERSED_DEPTH,
            PrismCapability.TRANSIENT_GPU_RESOURCES,
            PrismCapability.RESOURCE_ALIASING,
            PrismCapability.GPU_BUFFERS,
            PrismCapability.SHADER_PACK_RUNTIME,
            PrismCapability.SHADER_HOT_RELOAD,
            PrismCapability.FULLSCREEN_SHADER_PASSES,
            PrismCapability.SCENE_TERRAIN_PIPELINES,
            PrismCapability.NATIVE_PROGRAM_SETS,
            PrismCapability.MANIFESTLESS_NATIVE_PACKS,
            PrismCapability.LEGACY_PACK_INSPECTION,
            PrismCapability.WORLD_RENDER_PIPELINE,
            PrismCapability.WORLD_RENDER_PHASE_TRACKING,
            PrismCapability.FEATURE_RENDER_MODEL_PIPELINES,
            PrismCapability.DYNAMIC_SCENE_PIPELINE_VARIANTS,
            PrismCapability.ENTITY_SCENE_PIPELINES,
            PrismCapability.BLOCK_ENTITY_SCENE_PIPELINES,
            PrismCapability.SEMANTIC_SCENE_RESOURCES,
            PrismCapability.MULTIPASS_SHADER_PACKS,
            PrismCapability.PACK_FRAME_UNIFORMS,
            PrismCapability.PACK_TEMPORAL_HISTORY,
            PrismCapability.PACK_TEXTURE_ASSETS,
            PrismCapability.SCENE_PROJECTION_JITTER,
            PrismCapability.PACK_DYNAMIC_RESOLUTION,
            PrismCapability.SHADER_PACK_ARCHIVES,
            PrismCapability.LIVE_SHADER_LIBRARY,
            PrismCapability.SHADER_PACK_VERSIONING,
            PrismCapability.PACK_SETTINGS,
            PrismCapability.CREATOR_CPU_PROFILING,
            PrismCapability.CREATOR_GPU_PROFILING,
            PrismCapability.PIPELINE_CACHE_STATS,
            PrismCapability.RENDER_GRAPH_DIAGNOSTICS,
            PrismCapability.OFFSCREEN_RENDER_TARGETS,
            PrismCapability.MULTIPLE_RENDER_TARGETS,
            PrismCapability.SCENE_GBUFFER_ATTACHMENTS,
            PrismCapability.VANILLA_SCENE_REPLACEMENT,
            PrismCapability.MIPMAPPED_TEXTURES,
            PrismCapability.GPU_TIMESTAMP_QUERIES,
            PrismCapability.SHADOW_CASCADE_HELPERS,
            PrismCapability.SHADOW_ATLAS_HELPERS,
            PrismCapability.SHADOW_CASTER_CLASSIFICATION,
            PrismCapability.SHADOW_VISIBILITY_PLANNING,
            PrismCapability.CLIP_CONVENTION_HELPERS,
            PrismCapability.FRUSTUM_CULLING_HELPERS,
            PrismCapability.TEMPORAL_HELPERS,
            PrismCapability.TEMPORAL_INVALIDATION_REASONS,
            PrismCapability.CUBEMAP_TEXTURES));

    private PrismMinecraft26_2Capabilities() {}

    /** Portable Blaze3D baseline; Vulkan-native compute is negotiated separately at runtime. */
    public static Set<PrismCapability> proven() {
        return proven(false);
    }

    public static Set<PrismCapability> proven(boolean nativeVulkanBackend) {
        EnumSet<PrismCapability> result = EnumSet.copyOf(BASE_PROVEN);
        if (nativeVulkanBackend) {
            result.add(PrismCapability.VULKAN_BACKEND);
            result.add(PrismCapability.AUXILIARY_TERRAIN_VIEWS);
            result.add(PrismCapability.LAYERED_TERRAIN_VIEWS);
            result.add(PrismCapability.CAPTURED_MODEL_VIEWS);
            result.add(PrismCapability.RESIDENT_MODEL_VIEWS);
            result.add(PrismCapability.MODEL_VISIBILITY_VOLUMES);
            result.add(PrismCapability.DEPTH_ONLY_RENDER_TARGETS);
            result.add(PrismCapability.EXPLICIT_PASS_DEPENDENCIES);
            result.add(PrismCapability.SCENE_HIERARCHICAL_DEPTH);
            result.add(PrismCapability.MODEL_DEFORMATION_MOTION);
            result.add(PrismCapability.COMPUTE_PIPELINES);
            result.add(PrismCapability.STORAGE_BUFFERS);
            result.add(PrismCapability.STORAGE_IMAGES);
            result.add(PrismCapability.TEXTURE_ARRAYS);
            result.add(PrismCapability.TEXTURE_3D);
        }
        return Collections.unmodifiableSet(result);
    }
}
