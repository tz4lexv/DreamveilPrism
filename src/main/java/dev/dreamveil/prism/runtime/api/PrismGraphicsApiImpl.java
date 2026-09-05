package dev.dreamveil.prism.runtime.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.api.graphics.PrismGraphicsApi;
import dev.dreamveil.prism.api.graphics.PrismGraphicsFeature;
import dev.dreamveil.prism.backend.PrismBackendInfo;

public final class PrismGraphicsApiImpl implements PrismGraphicsApi {
    private final Set<PrismGraphicsFeature> features;

    public PrismGraphicsApiImpl(PrismBackendInfo backend) {
        EnumSet<PrismGraphicsFeature> available = EnumSet.noneOf(PrismGraphicsFeature.class);
        map(backend, available, PrismCapability.CUSTOM_RENDER_PIPELINES, PrismGraphicsFeature.GRAPHICS_PIPELINES);
        map(backend, available, PrismCapability.OFFSCREEN_RENDER_TARGETS, PrismGraphicsFeature.OFFSCREEN_RENDER_TARGETS);
        map(backend, available, PrismCapability.DEPTH_ONLY_RENDER_TARGETS, PrismGraphicsFeature.DEPTH_ONLY_RENDER_TARGETS);
        map(backend, available, PrismCapability.MULTIPLE_RENDER_TARGETS, PrismGraphicsFeature.MULTIPLE_RENDER_TARGETS);
        map(backend, available, PrismCapability.MIPMAPPED_TEXTURES, PrismGraphicsFeature.MIPMAPPED_TEXTURES);
        map(backend, available, PrismCapability.INDIRECT_DRAWS, PrismGraphicsFeature.INDIRECT_DRAWS);
        map(backend, available, PrismCapability.MULTI_DRAW_INDIRECT, PrismGraphicsFeature.MULTI_DRAW_INDIRECT);
        map(backend, available, PrismCapability.GPU_TIMESTAMP_QUERIES, PrismGraphicsFeature.GPU_TIMESTAMP_QUERIES);
        map(backend, available, PrismCapability.RESOURCE_VIEWS, PrismGraphicsFeature.RESOURCE_VIEWS);
        map(backend, available, PrismCapability.ATTACHMENT_LOAD_STORE, PrismGraphicsFeature.EXPLICIT_ATTACHMENT_LOAD_STORE);
        map(backend, available, PrismCapability.CUBEMAP_TEXTURES, PrismGraphicsFeature.CUBEMAP_TEXTURES);
        map(backend, available, PrismCapability.TEXTURE_ARRAYS, PrismGraphicsFeature.TEXTURE_ARRAYS);
        map(backend, available, PrismCapability.TEXTURE_3D, PrismGraphicsFeature.TEXTURE_3D);
        map(backend, available, PrismCapability.COMPARISON_SAMPLERS, PrismGraphicsFeature.COMPARISON_SAMPLERS);
        map(backend, available, PrismCapability.COMPUTE_PIPELINES, PrismGraphicsFeature.COMPUTE_PIPELINES);
        map(backend, available, PrismCapability.STORAGE_BUFFERS, PrismGraphicsFeature.STORAGE_BUFFERS);
        map(backend, available, PrismCapability.STORAGE_IMAGES, PrismGraphicsFeature.STORAGE_IMAGES);
        map(backend, available, PrismCapability.AUXILIARY_SCENE_VIEWS, PrismGraphicsFeature.AUXILIARY_SCENE_VIEWS);
        this.features = Collections.unmodifiableSet(available);
    }

    private static void map(PrismBackendInfo backend, EnumSet<PrismGraphicsFeature> target,
            PrismCapability capability, PrismGraphicsFeature feature) {
        if (backend.supports(capability)) target.add(feature);
    }

    @Override
    public Set<PrismGraphicsFeature> features() {
        return features;
    }
}
