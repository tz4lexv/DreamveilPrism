package dev.dreamveil.prism.api.resource;

import java.util.EnumSet;
import java.util.Set;

/** Descriptor factories shared by graph builders, pack loaders and creator tooling. */
public interface PrismResourceApi {
    default PrismTextureDescriptor colorAttachment(PrismTextureExtent extent, PrismTextureFormat format) {
        if (format == null || !format.isColor()) {
            throw new IllegalArgumentException("Color attachment requires a color format");
        }
        return new PrismTextureDescriptor(
                extent,
                format,
                EnumSet.of(
                        PrismTextureUsage.RENDER_ATTACHMENT,
                        PrismTextureUsage.SAMPLED,
                        PrismTextureUsage.COPY_SRC,
                        PrismTextureUsage.COPY_DST),
                1);
    }

    default PrismTextureDescriptor depthAttachment(PrismTextureExtent extent, PrismTextureFormat format) {
        if (format == null || !format.hasDepthAspect()) {
            throw new IllegalArgumentException("Depth attachment requires a depth format");
        }
        return new PrismTextureDescriptor(
                extent,
                format,
                EnumSet.of(
                        PrismTextureUsage.RENDER_ATTACHMENT,
                        PrismTextureUsage.SAMPLED,
                        PrismTextureUsage.COPY_SRC,
                        PrismTextureUsage.COPY_DST),
                1);
    }

    default PrismTextureDescriptor texture(
            PrismTextureExtent extent,
            PrismTextureFormat format,
            Set<PrismTextureUsage> usages,
            int mipLevels) {
        return new PrismTextureDescriptor(extent, format, usages, mipLevels);
    }

    /** Added in API 1.4. Rich descriptor for capability-gated arrays/3D/cube resources. */
    default PrismTextureResourceDescriptor textureResource(
            PrismTextureExtent extent,
            PrismTextureGeometry geometry,
            PrismTextureFormat format,
            Set<PrismTextureUsage> usages,
            int mipLevels) {
        return new PrismTextureResourceDescriptor(extent, geometry, format, usages, mipLevels);
    }

    default PrismBufferDescriptor buffer(long sizeBytes, Set<PrismBufferUsage> usages) {
        return new PrismBufferDescriptor(sizeBytes, usages);
    }
}
