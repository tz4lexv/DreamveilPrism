package dev.dreamveil.prism.api.graphics;

import java.util.Set;

/**
 * Capability-driven graphics foundation. This API intentionally exposes no Vulkan/OpenGL/Minecraft objects.
 * It describes what the current Prism bridge can safely provide to packs and creator integrations.
 */
public interface PrismGraphicsApi {
    PrismGraphicsApi EMPTY = new PrismGraphicsApi() {
        @Override public Set<PrismGraphicsFeature> features() { return Set.of(); }
    };

    Set<PrismGraphicsFeature> features();

    default boolean supports(PrismGraphicsFeature feature) {
        return feature != null && features().contains(feature);
    }

    default PrismSamplerDescriptor linearClampSampler() {
        return PrismSamplerDescriptor.linearClamp();
    }

    default PrismSamplerDescriptor nearestClampSampler() {
        return PrismSamplerDescriptor.nearestClamp();
    }

    /** API 1.4 descriptor helper: explicit load/store semantics for a preserved attachment. */
    default PrismAttachmentDescriptor loadStoreAttachment() {
        return PrismAttachmentDescriptor.loadStore();
    }

    /** API 1.4 validates a view range without creating backend objects. */
    default void validateTextureView(
            dev.dreamveil.prism.api.resource.PrismResourceViewDescriptor view,
            int textureMipLevels,
            int textureArrayLayers) {
        if (view == null) throw new IllegalArgumentException("Texture view descriptor must not be null");
        view.validateAgainst(textureMipLevels, textureArrayLayers);
    }
}
