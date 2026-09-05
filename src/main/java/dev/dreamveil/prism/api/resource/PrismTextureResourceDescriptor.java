package dev.dreamveil.prism.api.resource;

import java.util.Set;

/**
 * Rich API 1.4 texture descriptor. Existing PrismTextureDescriptor remains the compatibility 2D form.
 * Backend creation is still capability-gated and Prism does not claim unsupported dimensions.
 */
public record PrismTextureResourceDescriptor(
        PrismTextureExtent extent,
        PrismTextureGeometry geometry,
        PrismTextureFormat format,
        Set<PrismTextureUsage> usages,
        int mipLevels) {

    public PrismTextureResourceDescriptor {
        if (extent == null || geometry == null || format == null) {
            throw new IllegalArgumentException("Texture extent/geometry/format must not be null");
        }
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Texture usages must not be empty");
        }
        usages = Set.copyOf(usages);
        if (mipLevels < 1) throw new IllegalArgumentException("Texture mipLevels must be >= 1");
    }

    public static PrismTextureResourceDescriptor from2D(PrismTextureDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor must not be null");
        return new PrismTextureResourceDescriptor(
                descriptor.extent(),
                PrismTextureGeometry.texture2D(),
                descriptor.format(),
                descriptor.usages(),
                descriptor.mipLevels());
    }

    public PrismResourceViewDescriptor fullView() {
        return new PrismResourceViewDescriptor(0, mipLevels, 0, geometry.arrayLayers());
    }
}
