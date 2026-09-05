package dev.dreamveil.prism.runtime.api;

import java.util.EnumSet;

import dev.dreamveil.prism.api.graph.PrismResourceAccess;
import dev.dreamveil.prism.api.resource.PrismBufferDescriptor;
import dev.dreamveil.prism.api.resource.PrismBufferUsage;
import dev.dreamveil.prism.api.resource.PrismTextureDescriptor;
import dev.dreamveil.prism.api.resource.PrismTextureExtent;
import dev.dreamveil.prism.api.resource.PrismTextureUsage;
import dev.dreamveil.prism.graph.PrismBufferDesc;
import dev.dreamveil.prism.graph.PrismTextureDesc;

final class PrismApiAdapters {
    private PrismApiAdapters() {
    }

    static PrismTextureDesc textureDescriptor(PrismTextureDescriptor descriptor) {
        EnumSet<dev.dreamveil.prism.graph.PrismTextureUsage> usages =
                EnumSet.noneOf(dev.dreamveil.prism.graph.PrismTextureUsage.class);
        for (PrismTextureUsage usage : descriptor.usages()) {
            usages.add(dev.dreamveil.prism.graph.PrismTextureUsage.valueOf(usage.name()));
        }
        return new PrismTextureDesc(
                textureExtent(descriptor.extent()),
                dev.dreamveil.prism.graph.PrismTextureFormat.valueOf(descriptor.format().name()),
                usages,
                descriptor.mipLevels());
    }

    static PrismBufferDesc bufferDescriptor(PrismBufferDescriptor descriptor) {
        EnumSet<dev.dreamveil.prism.graph.PrismBufferUsage> usages =
                EnumSet.noneOf(dev.dreamveil.prism.graph.PrismBufferUsage.class);
        for (PrismBufferUsage usage : descriptor.usages()) {
            usages.add(dev.dreamveil.prism.graph.PrismBufferUsage.valueOf(usage.name()));
        }
        return new PrismBufferDesc(descriptor.sizeBytes(), usages);
    }

    static dev.dreamveil.prism.graph.PrismResourceAccess resourceAccess(PrismResourceAccess access) {
        return dev.dreamveil.prism.graph.PrismResourceAccess.valueOf(access.name());
    }

    private static dev.dreamveil.prism.graph.PrismTextureExtent textureExtent(PrismTextureExtent extent) {
        return switch (extent.mode()) {
            case ABSOLUTE -> dev.dreamveil.prism.graph.PrismTextureExtent.absolute(extent.width(), extent.height());
            case RELATIVE_TO_MAIN_TARGET ->
                    dev.dreamveil.prism.graph.PrismTextureExtent.relative(extent.scaleX(), extent.scaleY());
        };
    }
}
