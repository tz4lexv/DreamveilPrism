package dev.dreamveil.prism.graph;

import java.util.EnumSet;
import java.util.Set;

/** Immutable backend-neutral description of a Prism-owned texture. */
public record PrismTextureDesc(
        PrismTextureExtent extent,
        PrismTextureFormat format,
        Set<PrismTextureUsage> usages,
        int mipLevels) {

    public PrismTextureDesc {
        if (extent == null) {
            throw new IllegalArgumentException("Texture extent must not be null");
        }
        if (format == null) {
            throw new IllegalArgumentException("Texture format must not be null");
        }
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Texture usages must not be empty");
        }
        usages = Set.copyOf(usages);
        if (mipLevels < 1) {
            throw new IllegalArgumentException("Texture mipLevels must be >= 1");
        }
    }

    public static PrismTextureDesc colorAttachment(PrismTextureFormat format) {
        return colorAttachment(PrismTextureExtent.relative(1.0), format);
    }

    public static PrismTextureDesc colorAttachment(PrismTextureExtent extent, PrismTextureFormat format) {
        if (format == null || !format.isColor()) {
            throw new IllegalArgumentException("Color attachment requires a color format");
        }
        return new PrismTextureDesc(
                extent,
                format,
                EnumSet.of(
                        PrismTextureUsage.RENDER_ATTACHMENT,
                        PrismTextureUsage.SAMPLED,
                        PrismTextureUsage.COPY_SRC,
                        PrismTextureUsage.COPY_DST),
                1);
    }

    public static PrismTextureDesc depthAttachment(PrismTextureExtent extent, PrismTextureFormat format) {
        if (format == null || !format.hasDepthAspect()) {
            throw new IllegalArgumentException("Depth attachment requires a depth format");
        }
        return new PrismTextureDesc(
                extent,
                format,
                EnumSet.of(
                        PrismTextureUsage.RENDER_ATTACHMENT,
                        PrismTextureUsage.SAMPLED,
                        PrismTextureUsage.COPY_SRC,
                        PrismTextureUsage.COPY_DST),
                1);
    }
}
